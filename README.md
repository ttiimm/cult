# ⛧ Cult ⛧

Cargo for the Java ecosystem: an easy to use alternative to Ant/Maven/Gradle/etc for small projects.

We blindly follow Cargo like any good [Cargo Cult](https://en.wikipedia.org/wiki/Cargo_cult_programming). 

This project is somewhat tongue-in-cheek, but there is a lack of simple tooling within the Java ecosystem that perhaps 
Cult could help supplement. My biggest gripe with Ant/Maven/Gradle is that I tend not to spend enough time with the tools
to learn them deeply and end up copying-pasting between my projects or examples online. I'd rather have a small tool that
is easy to get started with, works for most simple projects, and includes some basic features like testing.

Another objective is to re-implement parts of Cargo, so that I can learn that tool in a bit more depth.

The project has also been a good excuse to learn and try some of the new experimental features in Java 22 of which there appear
to be some experimental features to make programming in Java less verbose and somewhat easier to get started using. This kinda
lines up with my objective to build an ergonomic build tool, so I'm going with it.

# Getting Started

Download the latest version from the releases page or build one of your own.

To start a new Cult (project):

```bash
$ cult new hail-cult
    Creating `hail-cult` project
$ tree hail-cult
hail-cult
├── Cult.toml
└── src
    └── Main.java

2 directories, 2 files
```

A Cult project uses a standard directory layout and TOML configuration that contains everything needed to build, test, 
and run the project.

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

You can compile the project using the `build` command, which results in a runnable jar.

```bash
$ cd hail-cult
$ cult build
    Compiling hail-cult v0.1.0 (/Users/tim/Projects/cult-tests/hail-cult)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.21s
# the --enable-preview is only required until the expirmental features are adopted
$ java --enable-preview -jar target/jar/hail-cult-0.1.0.jar
Hail, World!
```

Cult can even run the project for you.

```bash
$ cult run
    Compiling hail-cult v0.1.0 (/Users/tim/Projects/cult-tests/hail-cult)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.21s
        Running `target/jar/hail-cult-0.1.0.jar`
Hail, World!
```

You can clean up all artifacts created by Cult using `clean`.

```bash
$ cult clean
$ ls target
ls: target: No such file or directory
```

# Testing with Cult

One feature of Cargo I find really nice is that testing is baked in, so I wanted a way to easily allow folks to test their project
right out of the gate. To test applications, it requires adding a dependency on Cult

```toml
[package]
name = "project-with-tests"
version = "0.1.0"

[dependencies]
org.cult_cult = { path = "../../cult" }
```

And then using the @Test and @UnitTest annotations within the code. Similar to Rust, I wanted a way to allow the tests to co-reside with
the code under test in the same file. Here's a simple example

```java
import org.cult.UnitTest;
import org.cult.Tests;

import static org.cult.Lib.assertEquals;

void main() {
    System.out.println(add(1, 2));
}

static int add(int a, int b) {
    return a + b;
}

@Tests
static class TestCases {

    @UnitTest
    static void testAdd() {
        var result = add(2, 4);
        assertEquals(result, 5);
    }
}
```

When tested, this should lead to an error.

```
$ cult test
    Compiling with-tests v0.1.0 (/Users/tim/Projects/cult-tests/with-tests)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.39s
    running tests
F
error: test class Main$TestCases#testAdd failed
Expected 6 but got 5
```

Once fixed

```
$ cult test
    Compiling with-tests v0.1.0 (/Users/tim/Projects/cult-tests/with-tests)
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 0.29s
    running tests
.
```

# Developing Cult

Cult uses Cult to structure and build itself. Here are the steps to bootstrap the initial version of the jar.

The only pre-requisite to install is a version JDK 22.

Cult can be run using, which will build the jar.

```bash
$ java --enable-preview --source 22 src/Main.java build --fat
    Compiling cult vx.y.z (/Users/tim/Projects/cult)
Note: src/org/cult/Lib.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
Note: src/Main.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
Note: src/bin/Tester.java uses preview features of Java SE 22.
Note: Recompile with -Xlint:preview for details.
    Finished build in 1.18s
```

Afterwards, use the jar to continue development....

```bash
$ cp target/jar/cult-x.y.z.jar .

# Add a new feature

$ java --enable-preview -jar cult-x.y.z.jar build
...
```

....or the jar can be used to build a native executable version. This will require a version of Graalvm to use the
command `native-image`. 

```
$ native-image --enable-preview -jar target/jar/cult-0.3.0.jar
========================================================================================================================
GraalVM Native Image: Generating 'cult-0.3.0' (executable)...
========================================================================================================================
...
------------------------------------------------------------------------------------------------------------------------
Produced artifacts:
 /Users/tim/Projects/cult/cult-0.3.0 (executable)
========================================================================================================================

$ ./cult-0.3.0 -h
A simple Java package manager
```

The native application can be used to build other Cult projects. In order to test them, it requires the Cult library is added
as a dependency.

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
