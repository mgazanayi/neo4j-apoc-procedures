package apoc.trigger;

import apoc.ApocConfiguration;
import apoc.Description;
import apoc.coll.SetBackedList;
import apoc.util.Util;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.event.LabelEntry;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventHandler;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.impl.core.GraphProperties;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
//import org.neo4j.procedure.Mode;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static apoc.util.Util.map;

/**
 * @author mh
 * @since 20.09.16
 */
public class Trigger {

    public static class TriggerInfo {
        public String name;
        public String query;
        public Map<String,Object> selector;
        public boolean installed;
        public boolean paused;

        public TriggerInfo(String name, String query, Map<String, Object> selector, boolean installed, boolean paused) {
            this.name = name;
            this.query = query;
            this.selector = selector;
            this.installed = installed;
            this.paused = paused;
        }
    }

    @Context public GraphDatabaseService db;

    @UserFunction
    @Description("function to filter labelEntries by label, to be used within a trigger statement with {assignedLabels}, {removedLabels}, {assigned/removedNodeProperties}")
    public List<Node> nodesByLabel(@Name("labelEntries") Object entries, @Name("label") String labelString) {
        if (!(entries instanceof Map)) return Collections.emptyList();
        Map map = (Map) entries;
        if (map.isEmpty()) return Collections.emptyList();
        Object result = ((Map) entries).get(labelString);
        if (result instanceof List) return (List<Node>) result;
        Object anEntry = map.values().iterator().next();

        if (anEntry instanceof List) {
            List list = (List) anEntry;
            if (!list.isEmpty()) {
                if (list.get(0) instanceof Map) {
                    Set<Node> nodeSet = new HashSet<>(100);
                    Label label = labelString == null ? null : Label.label(labelString);
                    for (List<Map<String,Object>> entry : (Collection<List<Map<String,Object>>>) map.values()) {
                        for (Map<String, Object> propertyEntry : entry) {
                            Object node = propertyEntry.get("node");
                            if (node instanceof Node && (label == null || ((Node)node).hasLabel(label))) {
                                nodeSet.add((Node)node);
                            }
                        }
                    }
                    if (!nodeSet.isEmpty()) return new SetBackedList<>(nodeSet);
                } else if (list.get(0) instanceof Node) {
                    if (labelString==null) {
                        Set<Node> nodeSet = new HashSet<>(map.size()*list.size());
                        map.values().forEach((l) -> nodeSet.addAll((Collection<Node>)l));
                        return new SetBackedList<>(nodeSet);
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    @UserFunction
    @Description("function to filter propertyEntries by property-key, to be used within a trigger statement with {assignedNode/RelationshipProperties} and {removedNode/RelationshipProperties}. Returns [{old,new,key,node,relationship}]")
    public List<Map<String,Object>> propertiesByKey(@Name("propertyEntries") Map<String,List<Map<String,Object>>> propertyEntries, @Name("key") String key) {
        return propertyEntries.getOrDefault(key,Collections.emptyList());
    }

    @Procedure(mode = Mode.WRITE)
    @Description("add a trigger statement under a name, in the statement you can use {createdNodes}, {deletedNodes} etc., the selector is {phase:'before/after/rollback'} returns previous and new trigger information")
    public Stream<TriggerInfo> add(@Name("name") String name, @Name("statement") String statement, @Name(value = "selector"/*, defaultValue = "{}"*/)  Map<String,Object> selector) {
        Map<String, Object> removed = TriggerHandler.add(name, statement, selector);
        if (removed != null) {
            return Stream.of(
                    new TriggerInfo(name,(String)removed.get("statement"), (Map<String, Object>) removed.get("selector"),false, false),
                    new TriggerInfo(name,statement,selector,true, false));
        }
        return Stream.of(new TriggerInfo(name,statement,selector,true, false));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("remove previously added trigger, returns trigger information")
    public Stream<TriggerInfo> remove(@Name("name")String name) {
        Map<String, Object> removed = TriggerHandler.remove(name);
        if (removed == null) {
            Stream.of(new TriggerInfo(name, null, null, false, false));
        }
        return Stream.of(new TriggerInfo(name,(String)removed.get("statement"), (Map<String, Object>) removed.get("selector"),false, false));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("list all installed triggers")
    public Stream<TriggerInfo> list() {
        return TriggerHandler.list().entrySet().stream()
                .map( (e) -> new TriggerInfo(e.getKey(),(String)e.getValue().get("statement"),(Map<String,Object>)e.getValue().get("selector"),true, (Boolean) e.getValue().get("paused")));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("CALL apoc.trigger.pause(name) | it pauses the trigger")
    public Stream<TriggerInfo> pause(@Name("name")String name) {
        Map<String, Object> paused = TriggerHandler.paused(name);

        return Stream.of(new TriggerInfo(name,(String)paused.get("statement"), (Map<String,Object>) paused.get("selector"),true, true));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("CALL apoc.trigger.resume(name) | it resumes the paused trigger")
    public Stream<TriggerInfo> resume(@Name("name")String name) {
        Map<String, Object> resume = TriggerHandler.resume(name);

        return Stream.of(new TriggerInfo(name,(String)resume.get("statement"), (Map<String,Object>) resume.get("selector"),true, false));
    }

    public static class TriggerHandler implements TransactionEventHandler {
        public static final String APOC_TRIGGER = "apoc.trigger";
        static ConcurrentHashMap<String,Map<String,Object>> triggers = new ConcurrentHashMap(map("",map()));
        private static GraphProperties properties;
        private final Log log;
        public TriggerHandler(GraphDatabaseAPI api, Log log) {
            properties = api.getDependencyResolver().resolveDependency(NodeManager.class).newGraphProperties();
//            Pools.SCHEDULED.submit(() -> updateTriggers(null,null));
            this.log = log;
        }

        public static Map<String, Object> add(String name, String statement, Map<String,Object> selector) {
            return updateTriggers(name, map("statement", statement, "selector", selector, "paused", false));
        }
        public synchronized static Map<String, Object> remove(String name) {
            return updateTriggers(name,null);
        }

        public static Map<String, Object> paused(String name) {
            Map<String, Object> triggerToPause = triggers.get(name);
            updateTriggers(name, map("statement", triggerToPause.get("statement"), "selector", triggerToPause.get("selector"), "paused", true));
            return triggers.get(name);
        }

        public static Map<String, Object> resume(String name) {
            Map<String, Object> triggerToResume = triggers.get(name);
            updateTriggers(name, map("statement", triggerToResume.get("statement"), "selector", triggerToResume.get("selector"), "paused", false));
            return triggers.get(name);
        }

        private synchronized static Map<String, Object> updateTriggers(String name, Map<String, Object> value) {
            try (Transaction tx = properties.getGraphDatabase().beginTx()) {
                triggers.clear();
                String triggerProperty = (String) properties.getProperty(APOC_TRIGGER, "{}");
                triggers.putAll(Util.fromJson(triggerProperty,Map.class));
                Map<String,Object> previous = null;
                if (name != null) {
                    previous = (value == null) ? triggers.remove(name) : triggers.put(name, value);
                    if (value != null || previous != null) {
                        properties.setProperty(APOC_TRIGGER, Util.toJson(triggers));
                    }
                }
                tx.success();
                return previous;
            }
        }

        public static Map<String,Map<String,Object>> list() {
            updateTriggers(null,null);
            return triggers;
        }

        @Override
        public Object beforeCommit(TransactionData txData) throws Exception {
            executeTriggers(txData, "before");
            return null;
        }

        private void executeTriggers(TransactionData txData, String phase) {
            if (triggers.containsKey("")) updateTriggers(null,null);
            GraphDatabaseService db = properties.getGraphDatabase();
            Map<String,String> exceptions = new LinkedHashMap<>();
            Map<String, Object> params = txDataParams(txData, phase);
            triggers.forEach((name, data) -> {
                if( data.get("paused").equals(false)) {
                    try (Transaction tx = db.beginTx()) {
                        Map<String,Object> selector = (Map<String, Object>) data.get("selector");
                        if (when(selector, phase)) {
                            params.put("trigger", name);
                            Result result = db.execute((String) data.get("statement"), params);
                            Iterators.count(result);
                            result.close();
                        }
                        tx.success();
                    } catch(Exception e) {
                        log.warn("Error executing trigger "+name+" in phase "+phase,e);
                        exceptions.put(name, e.getMessage());
                    }
                }
            });
            if (!exceptions.isEmpty()) {
                throw new RuntimeException("Error executing triggers "+exceptions.toString());
            }
        }

        private boolean when(Map<String, Object> selector, String phase) {
            if (selector == null) return (phase.equals("before"));
            return selector.getOrDefault("phase", "before").equals(phase);
        }

        @Override
        public void afterCommit(TransactionData txData, Object state) {
            executeTriggers(txData, "after");
        }

        @Override
        public void afterRollback(TransactionData txData, Object state) {
            executeTriggers(txData, "rollback");
        }

    }

    private static Map<String, Object> txDataParams(TransactionData txData, String phase) {
        return map("transactionId", phase.equals("after") ? txData.getTransactionId() : -1,
                        "commitTime", phase.equals("after") ? txData.getCommitTime() : -1,
                        "createdNodes", txData.createdNodes(),
                        "createdRelationships", txData.createdRelationships(),
                        "deletedNodes", txData.deletedNodes(),
                        "deletedRelationships", txData.deletedRelationships(),
                        "removedLabels", aggregateLabels(txData.removedLabels()),
                        "removedNodeProperties", aggregatePropertyKeys(txData.removedNodeProperties(),true,true),
                        "removedRelationshipProperties", aggregatePropertyKeys(txData.removedRelationshipProperties(),false,true),
                        "assignedLabels", aggregateLabels(txData.assignedLabels()),
                        "assignedNodeProperties",aggregatePropertyKeys(txData.assignedNodeProperties(),true,false),
                        "assignedRelationshipProperties",aggregatePropertyKeys(txData.assignedRelationshipProperties(),false,false));
    }

    private static <T extends PropertyContainer> Map<String,List<Map<String,Object>>> aggregatePropertyKeys(Iterable<PropertyEntry<T>> entries, boolean nodes, boolean removed) {
        if (!entries.iterator().hasNext()) return Collections.emptyMap();
        Map<String,List<Map<String,Object>>> result = new HashMap<>();
        String entityType = nodes ? "node" : "relationship";
        for (PropertyEntry<T> entry : entries) {
            result.compute(entry.key(),
                    (k, v) -> {
                        if (v == null) v = new ArrayList<>(100);
                        Map<String, Object> map = map("key", k, entityType, entry.entity(), "old", entry.previouslyCommitedValue());
                        if (!removed) map.put("new", entry.value());
                        v.add(map);
                        return v;
                    });
        }
        return result;
    }
    private static Map<String,List<Node>> aggregateLabels(Iterable<LabelEntry> labelEntries) {
        if (!labelEntries.iterator().hasNext()) return Collections.emptyMap();
        Map<String,List<Node>> result = new HashMap<>();
        for (LabelEntry entry : labelEntries) {
            result.compute(entry.label().name(),
                    (k, v) -> {
                        if (v == null) v = new ArrayList<>(100);
                        v.add(entry.node());
                        return v;
                    });
        }
        return result;
    }

    public static class LifeCycle {
        private final GraphDatabaseAPI db;
        private final Log log;
        private TriggerHandler triggerHandler;

        public LifeCycle(GraphDatabaseAPI db, Log log) {
            this.db = db;
            this.log = log;
        }

        public void start() {
            boolean enabled = Util.toBoolean(ApocConfiguration.get("trigger.enabled", null));
            if (!enabled) return;
            triggerHandler = new Trigger.TriggerHandler(db,log);
            db.registerTransactionEventHandler(triggerHandler);
        }

        public void stop() {
            if (triggerHandler == null) return;
            db.unregisterTransactionEventHandler(triggerHandler);
        }
    }
}
