package ctstation;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Entry point: starts the embedded web/API server. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5220;
        Path dataDir = Paths.get("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": dataDir = Paths.get(args[++i]); break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: [--host H] [--port P] [--data DIR]");
                    System.exit(2);
            }
        }
        Service service = new Service(dataDir);
        if (!service.store.exists() || !service.store.seeded) {
            if (!service.store.exists() && service.store.crs.isEmpty()) {
                service.store.seeded = true;
                Seed.apply(service);
                service.store.save();
            }
        }
        WebServer server = new WebServer(service, host, port);
        server.start();
        System.out.println("坐标变换注册站 listening on http://" + host + ":" + port);
    }
}
