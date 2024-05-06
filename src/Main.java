import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

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
    Package aPackage = result.toPackage();
    if (aPackage == null) {
        return;
    }

    result = compile(aPackage);
    if (!result.isOk()) {
        return;
    }

    jar(aPackage);
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

Result compile(Package aPackage) {
    var cwd = System.getProperty("user.dir");
    System.out.println(STR."    Compiling \{aPackage.name} v\{aPackage.semver()} (\{cwd})");
    var compiler = ToolProvider.getSystemJavaCompiler();

    try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
        var compilationUnits = fileManager.getJavaFileObjects(Paths.get("src", "Main.java"));
        var options = Arrays.asList("--enable-preview", "--source", "22", "-d", "target/classes");
        var task = compiler.getTask(null, fileManager, null, options, null, compilationUnits);
        task.call();
    } catch (IOException e) {
        var sourcePath = STR."\{cwd + File.separator}src";
        System.err.println(STR."error: no source files detected at `\{sourcePath}`");
        return new Result(null);
    }

    return new Result(new Ok());
}

void jar(Package aPackage) {
}

record Result(Record record) {
    Package toPackage() {
        return (Package) record;
    }

    boolean isOk() {
        return record instanceof Ok;
    }
}

record Ok() {}

record Package(String name, Integer major, Integer minor, Integer patch) {

    String semver() {
        return STR."\{major}.\{minor}.\{patch}";
    }
}

