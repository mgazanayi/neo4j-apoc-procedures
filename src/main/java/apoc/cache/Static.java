package apoc.cache;

import apoc.ApocConfiguration;
import apoc.Description;
import apoc.result.ObjectResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author mh
 * @since 22.05.16
 */
public class Static {

    @Context
    public GraphDatabaseAPI db;

    private static Map<String,Object> storage = new HashMap<>();

    @Procedure("apoc.static.get")
    @Description("apoc.static.get(name) - returns statically stored value from config (apoc.static.<key>) or server lifetime storage")
    public Stream<ObjectResult> get(@Name("key") String key) {
        return Stream.of(new ObjectResult(storage.getOrDefault(key, fromConfig(key))));
    }

    private Object fromConfig(@Name("key") String key) {
        return ApocConfiguration.get("static."+key,null);
    }

    @Procedure("apoc.static.set")
    @Description("apoc.static.set(name, value) - stores value under key for server livetime storage, returns previously stored or configured value")
    public Stream<ObjectResult> set(@Name("key") String key, @Name("value") Object value) {
        Object previous = value == null ? storage.remove(key) : storage.put(key, value);
        return Stream.of(new ObjectResult(previous==null ? fromConfig(key) : previous));
    }
}
