# cult
Cargo for the Java ecosystem: an easy to use alternative to Ant/Maven/Gradle/etc for small projects.  

Initial goals and design philosophy from the [the Cargo book](https://doc.rust-lang.org/cargo/guide/why-cargo-exists.html).

> Cargo is the Rust package manager. It is a tool that allows Rust packages to declare their various dependencies and ensure that you’ll always get a repeatable build.
>
> To accomplish this goal, Cargo does four things:
>
> Introduces two metadata files with various bits of package information.
> Fetches and builds your package’s dependencies.
> Invokes rustc or another build tool with the correct parameters to build your package.
> Introduces conventions to make working with Rust packages easier.
> To a large extent, Cargo normalizes the commands needed to build a given program or library; this is one aspect to the above mentioned conventions. As we show later, the same command can be used to build different artifacts, regardless of their names. Rather than invoke rustc directly, we can instead invoke something generic such as cargo build and let cargo worry about constructing the correct rustc invocation. Furthermore, Cargo will automatically fetch from a registry any dependencies we have defined for our artifact, and arrange for them to be incorporated into our build as needed.
>
> It is only a slight exaggeration to say that once you know how to build one Cargo-based project, you know how to build all of them.
