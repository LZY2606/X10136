package coordstation.server;

import coordstation.json.Json;
import coordstation.registry.Registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** File-backed versioned persistence: the whole store is one canonical JSON document. */
public final class Store {
    private final Path file;

    public Store(Path file) {
        this.file = file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public void loadInto(Registry registry) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        registry.importStore(Json.parseObject(text));
    }

    public void save(Registry registry) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String text = Json.writePretty(registry.exportStore());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
