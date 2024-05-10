# ⛧ Cult ⛧

Cargo for the Java ecosystem: an easy to use alternative to Ant/Maven/Gradle/etc for small projects.

We blindly follow Cargo like any good [Cargo Cult](https://en.wikipedia.org/wiki/Cargo_cult_programming). 

This project is somewhat tongue-in-cheek, but there is a lack of simple tooling within the Java ecosystem that perhaps 
Cult could help supplement. The project has also been a good excuse to learn and try some of the new experimental features
in Java 22 of which there appear to be some experimental features to make programming in Java less verbose and somewhat 
easier to get started using. 

# Getting Started

The official build artifact of Cult is currently a Jar, so these commands are based off of using Java to run the jar. 
Eventually there may be a native executable to clean up some of that kludge, but until then, please bear with the mess.

To start a new Cult:

```bash
$ java --enable-preview -jar cult-0.1.0.jar new hail-cult
    Creating `hail-cult` project
$ tree hail-cult
hail-cult
├── Cult.toml
└── src
    └── Main.java

2 directories, 2 files
```

A Cult project uses a standard directory layout and TOML configuration that contains everything needed to build, test, 
and run the project. In the future the layout of the project will change to fit better with Java-like constructs, like 
packages and modules, but for now it is using this simple convention.

The `Cult.toml` contains the project's metadata needed for the build. The package nomenclature might go away in a later version
to avoid conflating with the concept of a Java package. In the future this is also where the project's dependencies will go.

```bash
$ cat hail-cult/Cult.toml
[package]
name = "hail-cult"
version = "0.1.0"
```

The source generated is a simple "Hello World" program that uses the experimental [implicitly declared classes and instance
main method](https://openjdk.org/jeps/463) feature available in Java 22. 

```bash
$ cat hail-cult/src/Main.java
void main() {
    System.out.println("Hail, World!");
}
```

You can compile the project using, which results in a runnable jar.

```bash
$ java --enable-preview -jar ../cult-0.1.0.jar build
    Compiling hail-cult v0.1.0 (/Users/tim/Projects/cult-tests/hail-cult)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.21s
$ java --enable-preview -jar target/jar/hail-cult-0.1.0.jar
Hail, World!
```

Cult can even run the project for you.

```bash
$ java --enable-preview -jar ../cult-0.1.0.jar run
    Compiling hail-cult v0.1.0 (/Users/tim/Projects/cult-tests/hail-cult)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.21s
        Running `target/jar/hail-cult-0.1.0.jar`
Hail, World!
```

You can clean up all artifacts created by Cult using `clean`.

```bash
$ java --enable-preview -jar ../cult-0.1.0.jar clean
$ ls target
ls: target: No such file or directory
```

That's the extent of the functionality for the `0.1` release. 

# Developing cult

Cult uses Cult to structure and build itself. Here are the steps to bootstrap the initial version of the jar.

The only pre-requisite is to install a version JDK 22.

Compile the Main class and use it to build an initial jar.

```bash
$ javac --enable-preview --source 22 -d target src/Main.java && java --enable-preview -cp target Main build
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Compiling cult v0.1.0 (/Users/tim/Projects/cult)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.33s
```

Afterwards, use the jar to continue development.

```bash
$ cp target/jar/cult-0.1.0.jar .

# Add a new feature

$ java --enable-preview -jar cult-0.1.0.jar build
...
```

# References

- [Build Systems a la Carte](https://www.microsoft.com/en-us/research/uploads/prod/2018/03/build-systems.pdf)

- Initial goals and design philosophy from the [the Cargo book](https://doc.rust-lang.org/cargo/guide/why-cargo-exists.html).

> Cargo is the Rust package manager. It is a tool that allows Rust packages to declare their various dependencies and ensure that you’ll always get a repeatable build.
>
> To accomplish this goal, Cargo does four things:
>
> * Introduces two metadata files with various bits of package information.
> * Fetches and builds your package’s dependencies.
> * Invokes rustc or another build tool with the correct parameters to build your package.
> * Introduces conventions to make working with Rust packages easier.
>
> To a large extent, Cargo normalizes the commands needed to build a given program or library; this is one aspect to the above mentioned conventions. As we show later, the same command can be used to build different artifacts, regardless of their names. Rather than invoke rustc directly, we can instead invoke something generic such as cargo build and let cargo worry about constructing the correct rustc invocation. Furthermore, Cargo will automatically fetch from a registry any dependencies we have defined for our artifact, and arrange for them to be incorporated into our build as needed.
>
> It is only a slight exaggeration to say that once you know how to build one Cargo-based project, you know how to build all of them.
