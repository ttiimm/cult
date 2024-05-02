import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
            System.exit(1);
        }

        switch (args[0]) {
            case "build":
                build();
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.out.println("A simple Java build tool");
    }

    private static void build() {
        Path toml = Paths.get("Cult.toml");
        try {
            System.out.println(Files.readAllLines(toml));
        } catch (IOException e) {
            String pwd = System.getProperty("user.dir");
            System.err.println(STR."error: could not find `Cult.toml` in `\{pwd}`");
        }
    }
}