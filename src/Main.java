import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

void main(String[] args) {
    if (args.length < 1) {
        usage();
        System.exit(1);
    }

    switch (args[0]) {
        case "new":
            if (args.length < 2) {
                System.err.println("error: missing <PATH> argument");
                System.exit(1);
            }
            newPackage(args[1]);
            break;
        case "clean":
            clean();
            break;
        case "build":
            build();
            break;
        case "run":
            Result result = build();
            if (result.isOk()) {
                run();
            }
            break;
        default:
            usage();
    }
}

void usage() {
    System.out.println("A simple Java package manager");
}

void clean() {
    try (var toClean = Files.walk(Paths.get("target"))) {
        toClean.sorted(Comparator.reverseOrder())
                .forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        System.err.println(STR."error: could not delete file `\{file}`");
                        System.err.println(e.getMessage());
                    }
            });
    } catch (IOException e) {
        System.err.println(STR."error: could not walk directory `target`");
        System.err.println(e.getMessage());
    }
}

// XXX: hrmmm calling package for now, but that's kind of confusing in a Java context
void newPackage(String path) {
    try {
        Path rootPath = Paths.get(path);
        Path packageName = rootPath.getFileName();
        System.out.println(STR."    Creating `\{packageName }` project");
        Files.createDirectories(Paths.get(path, "src"));
        Files.writeString(Paths.get(path, "Cult.toml"), STR."""
            [package]
            name = "\{packageName}"
            version = "0.1.0"
            """);
        Files.writeString(Paths.get(path, "src", "Main.java"), """
        void main() {
            System.out.println("Hail, World!");
        }
        """);
    } catch (IOException e) {
        System.err.println(STR."error: could not create directory under `\{path}`");
        System.err.println(e.getMessage());
    }
}

void run() {
    // XXX: assumes the build was run successfully and if that's the case, then know that a "package" exists
    Package aPackage = extractProject().toPackage();
    var jarPath = Paths.get("target", "jar", aPackage.getMainJarName());
    try {
        System.out.println(STR."        Running `\{jarPath}`");
        var process = new ProcessBuilder("java", "--enable-preview", "-jar", jarPath.toString()).start();
        var stdout = new ProcessPrinter(process.inputReader(), System.out);
        var stderr = new ProcessPrinter(process.errorReader(), System.err);
        Thread outThread = new Thread(stdout);
        Thread errThread = new Thread(stderr);
        outThread.start();
        errThread.start();
        process.waitFor();
        outThread.join();
        errThread.join();
    } catch (IOException e) {
        System.err.println(STR."error: could not find jar file at `\{jarPath}`");
    } catch (InterruptedException e) {
        System.err.println(STR."error: process was interrupted");
    }
}

Result build() {
    long start = System.currentTimeMillis();
    Result result = extractProject();
    Package aPackage = result.toPackage();
    if (aPackage == null) {
        return result;
    }

    result = compile(aPackage);
    if (!result.isOk()) {
        return result;
    }

    result = jar(aPackage);
    long end = System.currentTimeMillis();
    float duration = (float) (end - start) / 1000;
    if (result.isOk()) {
        System.out.printf("    Finished build in %.2fs%n", duration);
    } else {
        System.out.printf("    Finished build with errors in %.2fs%n", duration);
    }
    return result;
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

Result jar(Package aPackage) {
    var manifest = new Manifest();
    var attributes = manifest.getMainAttributes();
    attributes.putValue("Manifest-Version", "1.0");
    attributes.putValue("Created-By", "Cult");
    attributes.putValue("Main-Class", "Main");
    attributes.putValue("Name", aPackage.name);

    var jarDir = Paths.get("target", "jar");
    try {
        Files.createDirectories(jarDir);
    } catch (IOException e) {
        System.err.println(STR."error: could not create directory `\{jarDir}`");
        return new Result(null);
    }

    var jarPath = jarDir.resolve(aPackage.getMainJarName());
    try (
            var fos = new FileOutputStream(jarPath.toString());
            var jar = new JarOutputStream(fos, manifest)
    ) {
        var path = Paths.get("target", "classes");
        return addEntriesToJar(path, jar);
    } catch (IOException e) {
        System.err.println(STR."error: failed creating jar file. \{e.getMessage()}");
        return new Result(null);
    }
}

Result addEntriesToJar(Path path, JarOutputStream jar) {
    try (var paths = Files.walk(path)) {
            paths.filter(Files::isRegularFile)
                 .forEach(file -> {
                    try {
                        var entry = new JarEntry(path.relativize(file).toString());
                        jar.putNextEntry(entry);
                        jar.write(Files.readAllBytes(file));
                        jar.closeEntry();
                    } catch (IOException e) {
                        System.err.println(STR."error: could not add entry `\{file}` to jar");
                    }
                });
    } catch (IOException e) {
        System.err.println(STR."error: could not walk directory `\{path}`");
        return new Result(null);
    }
    return new Result(new Ok());
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

    public String getMainJarName() {
        return STR."\{name}-\{semver()}.jar";
    }
}

class ProcessPrinter implements Runnable {

    private final BufferedReader reader;
    private final PrintStream out;

    ProcessPrinter(BufferedReader reader, PrintStream out) {
        this.reader = reader;
        this.out = out;
    }

    @Override
    public void run() {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }
        } catch (IOException e) {
            System.err.println("error: could not read process output");
        }
    }
}
