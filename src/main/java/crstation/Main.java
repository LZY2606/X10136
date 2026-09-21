package crstation;

import java.nio.file.Path;

/** Entry point: starts the coordinate transform registry station web server. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        Path dataDir = Path.of("data");
        boolean seed = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                case "--no-seed" -> seed = false;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        ApiServer api = new ApiServer(dataDir, seed);
        api.start(host, port);
        System.out.println("坐标变换注册站 已启动: http://" + host + ":" + port);
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
    }
}
