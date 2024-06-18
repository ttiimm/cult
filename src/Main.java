import org.cult.UnitTest;
import org.cult.Tests;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.cult.Lib.assertEquals;

void main(String[] args) {
    if (args.length < 1) {
        usage();
        System.exit(1);
    }
    Result result;

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
            var executable = Artifact.JAR;
            if (args.length >= 2) {
                // meh -- native stuff is turning out to be harder than expected
//                if (args[1].equals("-n") || args[1].equals("--native")) {
//                    executable = Executable.NATIVE;
//                } else
                if (args[1].equals("-f") || args[1].equals("--fat")) {
                    executable = Artifact.FAT;
                } else {
                    System.err.println(STR."error: unknown build option `\{args[1]}`");
                    System.exit(1);
                }
            }
            build(executable);
            break;
        case "test":
            result = build(Artifact.JAR);
            if (result.isOk()) {
                test();
            }
            break;
        case "run":
            result = build(Artifact.JAR);
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

void test() {
    try {
        var mainClass = Class.forName("Main");
        var testClasses = Arrays.stream(mainClass.getDeclaredClasses())
                .filter(clazz -> clazz.getAnnotation(Tests.class) != null).toList();
        for (var testClass : testClasses) {
            var methods = testClass.getDeclaredMethods();
            for (var method : methods) {
                var unitTest = method.getAnnotation(UnitTest.class);
                if (unitTest != null) {
                    runTest(method);
                }
            }
        }
        System.out.println();
    } catch (ClassNotFoundException e) {
        System.err.println("error: could not find `Main` class");
    } catch (ReflectiveOperationException e) {
        System.err.println("error: could not run tests");
    }
}

void runTest(Method method) throws ReflectiveOperationException {
    try {
        method.invoke(null);
        System.out.print(".");
    } catch (InvocationTargetException e) {
        System.err.println("F");
        Throwable cause = e.getCause();
        if (cause instanceof AssertionError) {
            System.err.println(STR."error: test \{method.getDeclaringClass()}#\{method.getName()} failed");
            System.err.println(cause.getMessage());
        } else {
            throw new RuntimeException(cause);
        }
    }
}

void run() {
    // XXX: assumes the build was run successfully and if that's the case, then know that a "package" exists
    Package aPackage = extractProject(Paths.get(System.getProperty("user.dir"))).toPackage();
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

Result build(Artifact artifact) {
    var start = System.currentTimeMillis();
    var result = extractProject(Paths.get(System.getProperty("user.dir")));
    var aPackage = result.toPackage();
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

    result = findLibs();
    var libs = result.toLibSources();
    if (libs == null) {
        return result;
    }

    var libBundle = new LibBundle(aPackage, jars, libs);
    result = compile(libBundle);
    if (!result.isOk()) {
        return result;
    }

    var mainBundle = new MainBundle(aPackage, jars);
    result = compile(mainBundle);
    if (!result.isOk()) {
        return result;
    }

    if (!libBundle.getSource().isEmpty()) {
        result = jarLib(aPackage);
        if (!result.isOk()) {
            return result;
        }
    }

    result = jar(aPackage, jars, artifact);
    var end = System.currentTimeMillis();
    var duration = (float) (end - start) / 1000;
    if (result.isOk()) {
        System.out.printf("    Finished build in %.2fs%n", duration);
    } else {
        System.out.printf("    Finished build with errors in %.2fs%n", duration);
    }
    return result;
}

Result findLibs() {
    List<Path> libs;
    try (var paths = Files.walk(Paths.get("src"))) {
        libs = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().endsWith("Main.java"))
                .toList();
    } catch (IOException e) {
        System.err.println("error: could not walk directory `src/`");
        return new Result(null);
    }
    return new Result(new LibSources(libs));
}

static Result extractProject(Path root) {
    try {
        Path toml = root.resolve("Cult.toml");
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
        System.err.println(STR."error: could not find `Cult.toml` in `\{root}`");
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

            String[] split = line.split("=");
            if (inDependencies && split.length >= 2) {
                var identifier = split[0].replace("\"", "").trim();

                if (identifier.split("_").length != 2) {
                    System.err.println(STR."error: expecting single '_' in dependency key, but it was: \{identifier}");
                    return new Result(null);
                }

                Dependency dependency;
                if (split.length == 2) {
                    var version = split[1].replace("\"", "").trim();
                    dependency = new Version(version);
                // XXX: going to need to start on parser soon
                } else if (split.length == 3 && split[1].startsWith(" { path")) {
                    var path = Paths.get(split[2].substring(2, split[2].length() - 3));
                    dependency = new LocalDir(path);
                } else {
                    System.err.println(STR."error: invalid dependency: \{line}");
                    return new Result(null);
                }

                dependencies.add(new ModuleId(identifier), dependency);
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
        var module = entry.getKey();
        var dependency = entry.getValue();
        var libJarPath = dependency.resolve(module);
        if (libJarPath.isPresent() && libJarPath.get().toFile().exists()) {
            paths.add(new LibInfo(libJarPath.get(), false));
        } else if (libJarPath.isEmpty()) {
            return new Result(null);
        } else {
            result = dependency.fetch(module, libJarPath.get());
            if (result.isOk()) {
                paths.add(new LibInfo(libJarPath.get(), false));
            } else {
                return new Result(null);
            }
        }
    }
    return new Result(new Jars(paths));
}

Result compile(Bundle bundle) {
    if (bundle.getSource().isEmpty()) {
        return new Result(new Ok());
    }

    var cwd = System.getProperty("user.dir");
    System.out.println(STR."    Compiling \{bundle.getName()} v\{bundle.getVersion()} (\{cwd})");

    try {
        var sourcePaths = bundle.getSource().stream().map(Path::toString).toList();

        // Using Process API instead of ToolProvider.getSystemJavaCompiler() because aspire to build a native image
        var javac = new ProcessBuilder(
                "javac",
                "--enable-preview",
                "--source", "22",
                "-cp", bundle.getClasspath(),
                "-d", bundle.outLocation()
        );
        javac.command().addAll(sourcePaths);
        var process = javac.start();
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

Result jarLib(Package aPackage) {
    var manifest = new Manifest();
    var attributes = manifest.getMainAttributes();
    attributes.putValue("Manifest-Version", "1.0");
    attributes.putValue("Created-By", "Cult 0.3.0");
    attributes.putValue("Name", aPackage.name + "-lib");

    var jarDir = Paths.get("target", "jar");
    var result = createDir(jarDir);
    if (!result.isOk()) {
        return result;
    }

    var classDirectory = "lib-classes";
    var jarName = aPackage.getLibJarName();
    return doJarring(jarDir, jarName, manifest, classDirectory);
}

Result jar(Package aPackage, Jars dependencies, Artifact artifact) {
    var needsFat = artifact == Artifact.FAT || artifact == Artifact.NATIVE;
    var manifest = new Manifest();
    var attributes = manifest.getMainAttributes();
    attributes.putValue("Manifest-Version", "1.0");
    attributes.putValue("Created-By", "Cult 0.3.0");
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

    var jarName = aPackage.getMainJarName();
    if (needsFat) {
        return doJarring(jarDir, jarName, manifest, "classes", "lib-classes");
    } else {
        return doJarring(jarDir, jarName, manifest, "classes");
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

private Result doJarring(Path jarDir, String jarName, Manifest manifest, String... classDirectory) {
    var jarPath = jarDir.resolve(jarName);
    try (
            var fos = new FileOutputStream(jarPath.toString());
            var jar = new JarOutputStream(fos, manifest)
    ) {
        Result result = new Result(null);
        for (var classesPath : classDirectory) {
            var path = Paths.get("target", classesPath);
            result = buildJar(path, jar);
            if (!result.isOk()) {
                return result;
            }
        }
        return result;
    } catch (IOException e) {
        System.err.println(STR."error: failed creating jar file. \{e.getMessage()}");
        return new Result(null);
    }
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
        System.err.println(STR."error: could not build jar `\{classesToJar}`");
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

    public LibSources toLibSources() {
        return (LibSources) record;
    }
}

record Ok() {}

private interface Bundle {
    String getClasspath();

    List<Path> getSource();

    String outLocation();

    String getName();

    String getVersion();
}

record MainBundle(Package aPackage, Jars jars) implements Bundle {

    public MainBundle(Package aPackage, Jars jars) {
        this.aPackage = aPackage;
        this.jars = jars;
    }

    public String getClasspath() {
        var dependencies = new ArrayList<>(jars.libs.stream().map(lib -> lib.path.toString()).toList());
        // any libs
        dependencies.add(Paths.get("target", "lib-classes").toString());
        return String.join(":", dependencies);
    }

    public List<Path> getSource() {
        return List.of(Paths.get("src", "Main.java"));
    }

    public String outLocation() {
        return STR."target\{File.separator}classes";
    }

    @Override
    public String getName() {
        return aPackage.name;
    }

    @Override
    public String getVersion() {
        return aPackage.semver();
    }

}

record LibBundle(Package aPackage, Jars jars, LibSources libs) implements Bundle {

    public LibBundle(Package aPackage, Jars jars, LibSources libs) {
        this.aPackage = aPackage;
        this.jars = jars;
        this.libs = libs;
    }

    @Override
    public String getClasspath() {
        var dependencies = new ArrayList<>(jars.libs.stream().map(lib -> lib.path.toString()).toList());
        // XXX; make sure there is always "" otherwise will bork on empty list
//        dependencies.add("\"\"");
        return String.join(":", dependencies);
    }

    @Override
    public List<Path> getSource() {
        return libs.sources;
    }

    @Override
    public String outLocation() {
        return STR."target\{File.separator}lib-classes";
    }

    @Override
    public String getName() {
        return aPackage.name;
    }

    @Override
    public String getVersion() {
        return aPackage.semver();
    }
}

record Package(String name, Version version) {
    String semver() {
        return version.semver();
    }

    public String getMainJarName() {
        return STR."\{name}-\{semver()}.jar";
    }

    public String getLibJarName() {
        return STR."\{name}-lib-\{semver()}.jar";
    }
}

private interface Dependency {

    Optional<Path> resolve(ModuleId module);

    Result fetch(ModuleId module, Path libJarPath);
}

record Version(Integer major, Integer minor, Integer patch) implements Dependency {

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

    @Override
    public Optional<Path> resolve(ModuleId module) {
        // XXX: maybe move this stuff into ModuleId
        var semver = semver();
        var jarName = STR."\{ module.name }-\{ semver }.jar";
        return Optional.of(Paths.get("target", "lib").resolve(jarName));
    }

    @Override
    public Result fetch(ModuleId module, Path libJarPath) {
        // XXX: maybe move this stuff into ModuleId
        var orgPath = String.join("/", module.organization.split("\\."));
        var semver = semver();
        var jarName = libJarPath.getFileName();
        String url = STR."https://repo1.maven.org/maven2/\{ orgPath }/\{ module.name }/\{ semver }/\{ jarName }";
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, libJarPath);
            return new Result(new Ok());
        } catch (IOException e) {
            System.err.println(STR."error: could not fetch library: \{url}");
            System.err.println(e.getMessage());
            return new Result(null);
        }
    }
}

record LocalDir(Path pathToRoot) implements Dependency {
    @Override
    public Optional<Path> resolve(ModuleId module) {
        Result result = extractProject(pathToRoot);
        var thePackage = result.toPackage();
        if (thePackage == null) {
            System.err.println(STR."error: unable to resolve Cult.toml from \{pathToRoot}");
            return Optional.empty();
        }
        var jarName = STR."\{thePackage.name()}-lib-\{thePackage.semver()}.jar";
        return Optional.of(pathToRoot.resolve("target", "jar", jarName));
    }

    @Override
    public Result fetch(ModuleId module, Path libJarPath) {
        // XXX: handle this later by building the project at this root or something
        throw new UnsupportedOperationException();
    }
}

record ModuleId(String organization, String name) {

    ModuleId(String identifier) {
        String[] idParts = identifier.split("_");
        this(idParts[0], idParts[1]);
    }
}

record Dependencies() {

    private static final Map<ModuleId, Dependency> dependencies = new HashMap<>();

    void add(ModuleId modId, Dependency dependency) {
        dependencies.put(modId, dependency);
    }

    public Map<ModuleId, Dependency> get() {
        return new HashMap<>(dependencies);
    }
}

record Jars(List<LibInfo> libs) {

}

record LibInfo(Path path, boolean wasCopied) {

}

record LibSources(List<Path> sources) {

}

enum Artifact {
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

@Tests
static class CultTests {

    @UnitTest
    static void testVersionOnlyMajor() {
        var version = new Version("3");
        assertEquals("3.00", version.semver());
    }

}
