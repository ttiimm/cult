import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.DownloadStatus;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.util.AbstractMessageLogger;
import org.apache.ivy.util.Message;

import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.*;
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
    Ivy ivy = Ivy.newInstance();
    try {
        ivy.configureDefault();
        Message.setDefaultLogger(new QuietLogger());
    } catch (ParseException | IOException e) {
        System.err.println(STR."error: could not configure Ivy");
        System.err.println(e.getMessage());
        return new Result(null);
    }

    var paths = new ArrayList<String>();
    for (var entry : dependencies.get().entrySet()) {
        try {
            ModuleId module = entry.getKey();
            Version version = entry.getValue();
            ModuleRevisionId mrid = ModuleRevisionId.newInstance(module.organization, module.name, version.semver());
            ResolveOptions resolveOptions = new ResolveOptions().setConfs(new String[]{"default"});
            ResolveReport resolveReport = ivy.resolve(mrid, resolveOptions, true);
            for (var report : resolveReport.getAllArtifactsReports()) {
                if (report.getDownloadStatus() != DownloadStatus.NO) {
                    System.out.println(STR."    \{report}");
                }
                // XXX: maybe need to filter based on the extension or some such?
                paths.add(report.getLocalFile().getAbsolutePath());
            }
        } catch (ParseException | IOException e) {
            System.err.println(STR."error: failed downloading dependency `\{entry}`");
            System.err.println(e.getMessage());
            return new Result(null);
        }
    }
    return new Result(new Jars(paths));
}

Result compile(Package aPackage, Jars jars) {
    var cwd = System.getProperty("user.dir");
    System.out.println(STR."    Compiling \{aPackage.name} v\{aPackage.semver()} (\{cwd})");
    var compiler = ToolProvider.getSystemJavaCompiler();

    try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
        var compilationUnits = fileManager.getJavaFileObjects(Paths.get("src", "Main.java"));
        String classpath = String.join(":", jars.paths);
        var options = Arrays.asList("--enable-preview", "--source", "22", "-cp", classpath, "-d", "target/classes");
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

    private static Map<ModuleId, Version> dependencies = new HashMap<>();

    void add(ModuleId modId, Version version) {
        dependencies.put(modId, version);
    }

    public Map<ModuleId, Version> get() {
        return new HashMap<>(dependencies);
    }
}

record Jars(List<String> paths) {

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

// Custom Logger for Ivy, maybe there's an easier way to configure this behavior?
class QuietLogger extends AbstractMessageLogger {

    @Override
    public void doProgress() {
        // Do nothing
    }

    @Override
    public void doEndProgress(String msg) {
        // Do nothing
    }

    @Override
    public void rawlog(String msg, int level) {
        if (level <= Message.MSG_ERR) {
            System.err.println(msg);
        }
    }

    @Override
    public void log(String msg, int level) {
        if (level <= Message.MSG_ERR) {
            System.err.println(msg);
        }
    }
}