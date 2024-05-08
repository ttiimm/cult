# cult
Cargo for the Java ecosystem: an easy to use alternative to Ant/Maven/Gradle/etc for small projects.  

# Building

This project uses Cult itself to build. Here are the steps to bootstrap the initial version of the jar.

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
