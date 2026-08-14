package stockapp.web;

import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Teaches Javalin to use Gson.
 *
 * <p>Javalin's built-in {@code ctx.json()} expects Jackson on the classpath.
 * This project uses Gson (it was already a dependency, and it serialises
 * records without an extra module), so the mapper is swapped at startup rather
 * than pulling in a second JSON library.
 *
 * <p>Only the serialising half is implemented: request bodies are read through
 * {@link Json#parseObject} so that a malformed field produces a precise 400
 * instead of a generic binding failure.
 */
public final class GsonMapper implements JsonMapper {

    @Override
    public String toJsonString(Object obj, Type type) {
        return Json.write(obj);
    }
}
