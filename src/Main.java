import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
            var executable = Executable.JAR;
            if (args.length >= 2) {
                // meh -- native stuff is turning out to be harder than expected
//                if (args[1].equals("-n") || args[1].equals("--native")) {
//                    executable = Executable.NATIVE;
//                } else
                if (args[1].equals("-f") || args[1].equals("--fat")) {
                    executable = Executable.FAT;
                } else {
                    System.err.println(STR."error: unknown build option `\{args[1]}`");
                    System.exit(1);
                }
            }
            build(executable);
            break;
        case "run":
            Result result = build(Executable.JAR);
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
        System.err.println("error: could not walk directory `target`");
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
        run(process);
    } catch (IOException e) {
        System.err.println(STR."error: could not find jar file at `\{jarPath}`");
    } catch (InterruptedException e) {
        System.err.println("error: process was interrupted");
    }
}

Result build(Executable executable) {
    long start = System.currentTimeMillis();
    Result result = extractProject();
    Package aPackage = result.toPackage();
    if (aPackage == null) {
        return result;
    }

    result = extractDependencies();
    var dependencies = result.toDependencies();
    if (dependencies == null) {
        return result;
    }

    result = fetch(dependencies);
    var jars = result.toJars();
    if (jars == null) {
        return result;
    }

    result = compile(aPackage, jars);
    if (!result.isOk()) {
        return result;
    }

    result = jar(aPackage, jars, executable);
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
            Version packageVersion = new Version(version);
            return new Result(new Package(name, packageVersion));
        }
    } catch (IOException e) {
        String pwd = System.getProperty("user.dir");
        System.err.println(STR."error: could not find `Cult.toml` in `\{pwd}`");
        return new Result(null);
    }
    return new Result(null);
}

Result extractDependencies() {
    var toml = Paths.get("Cult.toml");
    var inDependencies = false;
    var dependencies = new Dependencies();
    try {
        for (String line : Files.readAllLines(toml)) {
            if (line.contains("[dependencies]")) {
                inDependencies = true;
            } else if (line.contains("[") && line.contains("]")) {
                inDependencies = false;
            }

            if (inDependencies && line.split("=").length >= 2) {
                var identifier = line.split("=")[0].replace("\"", "").trim();

                if (identifier.split("_").length != 2) {
                    System.err.println(STR."error: expecting single '_' in dependency key, but it was: \{identifier}");
                    return new Result(null);
                }

                var version = line.split("=")[1].replace("\"", "").trim();
                dependencies.add(new ModuleId(identifier), new Version(version));
            }
        }

        return new Result(dependencies);
    } catch (IOException e) {
        var pwd = System.getProperty("user.dir");
        System.err.println(STR."error: could not find `Cult.toml` in `\{pwd}`");
        return new Result(null);
    }
}

Result fetch(Dependencies dependencies) {
    var paths = new ArrayList<LibInfo>();
    Path libDir = Paths.get("target", "lib");
    var result = createDir(libDir);
    if (!result.isOk()) {
        return result;
    }

    for (var entry : dependencies.get().entrySet()) {
        // XXX: maybe move this stuff into ModuleId
        var module = entry.getKey();
        var orgPath = String.join("/", module.organization.split("\\."));
        var version = entry.getValue();
        var semver = version.semver();
        var jarName = STR."\{ module.name }-\{ semver }.jar";

        var libJar = libDir.resolve(jarName);
        if (libJar.toFile().exists()) {
            paths.add(new LibInfo(libJar, false));
        } else {
            String url = STR."https://repo1.maven.org/maven2/\{ orgPath }/\{ module.name }/\{ semver }/\{ jarName }";
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, libJar);
                paths.add(new LibInfo(libJar, false));
            } catch (IOException e) {
                System.err.println(STR."error: could not fetch library: \{url}");
                System.err.println(e.getMessage());
                return new Result(null);
            }
        }
    }
    return new Result(new Jars(paths));
}

Result compile(Package aPackage, Jars jars) {
    var cwd = System.getProperty("user.dir");
    System.out.println(STR."    Compiling \{aPackage.name} v\{aPackage.semver()} (\{cwd})");

    try {
        var classpath = String.join(":", jars.libs.stream().map(lib -> lib.path.toString()).toList());
        // Using Process API instead of ToolProvider.getSystemJavaCompiler() because aspire to build a native image
        var process = new ProcessBuilder(
                "javac",
                "--enable-preview",
                "--source", "22",
                "-cp", classpath,
                "-d", STR."target\{File.separator}classes",
                Paths.get("src", "Main.java").toString()
            ).start();
        int exit = run(process);
        if (exit != 0) {
            System.err.println("error: failed to compile");
            return new Result(null);
        }
    } catch (InterruptedException | IOException e) {
        System.err.println("error: failed to compile");
        System.err.println(e.getMessage());
        return new Result(null);
    }
    return new Result(new Ok());
}

private int run(Process process) throws IOException, InterruptedException {
    // XXX: should try out the Project Loom stuff here
    var stdout = new ProcessPrinter(process.inputReader(), System.out);
    var stderr = new ProcessPrinter(process.errorReader(), System.err);
    Thread outThread = new Thread(stdout);
    Thread errThread = new Thread(stderr);
    outThread.start();
    errThread.start();
    process.waitFor();
    outThread.join();
    errThread.join();
    return process.exitValue();
}

Result jar(Package aPackage, Jars dependencies, Executable executable) {
    var needsFat = executable == Executable.FAT || executable == Executable.NATIVE;
    var manifest = new Manifest();
    var attributes = manifest.getMainAttributes();
    attributes.putValue("Manifest-Version", "1.0");
    attributes.putValue("Created-By", "Cult 0.1.0");
    attributes.putValue("Main-Class", "Main");
    attributes.putValue("Name", aPackage.name);

    var jarDir = Paths.get("target", "jar");
    var result = createDir(jarDir);
    if (!result.isOk()) {
        return result;
    }

    var libs = dependencies.libs();
    if (needsFat) {
        result = unpack(dependencies);
        if (!result.isOk()) {
            return result;
        }
    } else {
        var relativized = libs.stream().map(lib -> jarDir.relativize(lib.path)).map(Path::toString).toList();
        attributes.putValue("Class-Path", String.join(" ", relativized));
    }

    var jarPath = jarDir.resolve(aPackage.getMainJarName());
    try (
            var fos = new FileOutputStream(jarPath.toString());
            var jar = new JarOutputStream(fos, manifest)
    ) {
        var path = Paths.get("target", "classes");
        return buildJar(path, jar);
    } catch (IOException e) {
        System.err.println(STR."error: failed creating jar file. \{e.getMessage()}");
        return new Result(null);
    }
}

private static Result unpack(Jars dependencies) {
    var base = Paths.get("target", "classes");
    for (var lib : dependencies.libs()) {
        if (!lib.wasCopied) {
            continue;
        }
        var dependency = lib.path;
        try (var jar = new JarFile(dependency.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals("META-INF/MANIFEST.MF") ||
                        entry.getName().equals("module.properties")) {
                    continue;
                }

                var nameToUse = entry.getName();
                if (nameToUse.endsWith("LICENSE") || nameToUse.endsWith("NOTICE")) {
                    nameToUse = STR."\{nameToUse}_\{dependency.getFileName()}";
                }

                var entryDest = base.resolve(nameToUse);
                if (entry.isDirectory()) {
                    Files.createDirectories(entryDest);
                } else {
                    Files.createDirectories(entryDest.getParent());
                    try (InputStream input = jar.getInputStream(entry)) {
                        Files.copy(input, entryDest);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println(STR."error: could not unpack jar file `\{dependency}`");
            System.err.println(e.getMessage());
            return new Result(null);
        }

    }
    return new Result(new Ok());
}

//Result nativeImage(Package aPackage, Jars jars) {
//
//}

private static Result createDir(Path targetDir) {
    try {
        Files.createDirectories(targetDir);
    } catch (IOException e) {
        System.err.println(STR."error: could not create directory `\{targetDir}`");
        return new Result(null);
    }
    return new Result(new Ok());
}

private Result buildJar(Path classesToJar, JarOutputStream jar) {
    try (var paths = Files.walk(classesToJar)) {
            paths.filter(Files::isRegularFile)
                 .forEach(file -> {
                     try {
                         var entry = new JarEntry(classesToJar.relativize(file).toString());
                         jar.putNextEntry(entry);
                         jar.write(Files.readAllBytes(file));
                         jar.closeEntry();
                     } catch (IOException e) {
                         System.err.println(STR."error: could not add entry `\{file}` to jar");
                         System.err.println(e.getMessage());
                     }
                 });
    } catch (IOException e) {
        System.err.println(STR."error: could not walk directory `\{classesToJar}`");
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

    public Dependencies toDependencies() {
        return (Dependencies) record;
    }

    public Jars toJars() {
        return (Jars) record;
    }
}

record Ok() {}

record Package(String name, Version version) {
    String semver() {
        return version.semver();
    }

    public String getMainJarName() {
        return STR."\{name}-\{semver()}.jar";
    }
}

record Version(Integer major, Integer minor, Integer patch) {

    Version(String version)  {
        String[] semantic = version.split("\\.");
        int major, minor, patch;
        major = minor = patch = 0;
        switch (semantic.length) {
            case 3:
                patch = Integer.parseInt(semantic[2]);
            case 2:
                minor = Integer.parseInt(semantic[1]);
            case 1:
                major = Integer.parseInt(semantic[0]);
        }
        this(major, minor, patch);
    }

    String semver() {
        return STR."\{major}.\{minor}.\{patch}";
    }
}

record ModuleId(String organization, String name) {

    ModuleId(String identifier) {
        String[] idParts = identifier.split("_");
        this(idParts[0], idParts[1]);
    }
}

record Dependencies() {

    private static final Map<ModuleId, Version> dependencies = new HashMap<>();

    void add(ModuleId modId, Version version) {
        dependencies.put(modId, version);
    }

    public Map<ModuleId, Version> get() {
        return new HashMap<>(dependencies);
    }
}

record Jars(List<LibInfo> libs) {

}

record LibInfo(Path path, boolean wasCopied) {

}

enum Executable {
    JAR, FAT, NATIVE
}

static class ProcessPrinter implements Runnable {

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
