import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

void main(String[] args) {
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

void usage() {
    System.out.println("A simple Java package manager");
}

void build() {
    Result result = extractProject();
    Package aPackage = result.toProject();
    if (aPackage == null) {
        return;
    }

    System.out.println(aPackage);
}

Result extractProject() {
    try {
        Path toml = Paths.get("Cult.toml");
        boolean inProject = false;
        String name = null;
        String version = null;
        for (String line : Files.readAllLines(toml)) {
               if (line.contains("[package]")) {
                   inProject = true;
               } else if (line.contains("[") && line.contains("]")) {
                   inProject = false;
               }

               if (inProject && line.split("name = ").length >= 2) {
                   name = line.split("name = ")[1].replace("\"", "").trim();
               } else if (inProject && line.split("version = ").length >= 2) {
                   version = line.split("version = ")[1].replace("\"", "").trim();
               }
        }

        if (name != null && version != null) {
            String[] semantic = version.split("\\.");
            Integer major, minor, patch;
            major = minor = patch = 0;
            switch (semantic.length) {
                case 3:
                    patch = Integer.parseInt(semantic[2]);
                case 2:
                    minor = Integer.parseInt(semantic[1]);
                case 1:
                    major = Integer.parseInt(semantic[0]);
            }
            return new Result(new Package(name, major, minor, patch));
        }
    } catch (IOException e) {
        String pwd = System.getProperty("user.dir");
        System.err.println(STR."error: could not find `Cult.toml` in `\{pwd}`");
    }

    return new Result(null);
}

record Result(Record record) {
    Package toProject() {
        return (Package) record;
    }

}

record Package(String name, Integer major, Integer minor, Integer patch) {}
