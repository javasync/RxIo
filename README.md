# AsyncFileRw

[![Build Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3AAsyncFileRw&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.github.javasync%3AAsyncFileRw)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.github.javasync/AsyncFileRw.svg)](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22AsyncFileRw%22)
[![Coverage Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3AAsyncFileRw&metric=coverage)](https://sonarcloud.io/component_measures?id=com.github.javasync%3AAsyncFileRw&metric=Coverage)

The [`AsyncFiles`](src/main/java/org/javaync/io/AsyncFiles.java) class allows Java
applications to easily read/write files asynchronously and without blocking.
This is an equivalent to the standard JDK
[Files](https://docs.oracle.com/javase/10/docs/api/java/nio/file/Files.html)
class but using non-blocking IO.
In section [Usage](#Usage) we present some examples using the
[`AsyncFiles`](src/main/java/org/javaync/io/AsyncFiles.java)
class side by side with the corresponding blocking version of
[Files](https://docs.oracle.com/javase/10/docs/api/java/nio/file/Files.html).

## Installation

First, in order to include it to your Maven project,
simply add this dependency:

```xml
<dependency>
    <groupId>com.github.javasync</groupId>
    <artifactId>AsyncFileRw</artifactId>
    <version>1.1.3</version>
</dependency>
```

To add a dependency using Gradle:

```
dependencies {
  compile 'com.github.javasync:AsyncFileRw:1.0.0'
}
```

## Usage

### Writing lines into a file

<table class="table">
    <tr class="row">
        <td>
            <pre><code class="language-java">
Path path = Paths.get("output.txt")
List&ltString&gt data = asList("super", "brave", "isel", "gain");
AsyncFiles
    .write(path, data)
    .join(); // block if you want to wait for write completion
            </code></pre>
        </td>
        <td>
            <pre><code class="language-java">
Path path = Paths.get("output.txt")
List&ltString&gt data = asList("super", "brave", "isel", "gain");
Files.write(path, data);
&nbsp
&nbsp
            </code></pre>
        </td>
    <tr>
</table>

### Reading all bytes from one file and writing to another

<table class="table">
    <tr class="row">
        <td>
            <pre><code class="language-java">
Path in = Paths.get("input.txt");
Path out = Paths.get("output.txt");
AsyncFiles
        .readAllBytes(in)
        .thenCompose(bytes -> AsyncFiles.writeBytes(out, bytes))
        .join(); // block if you want to wait for write completion
            </code></pre>
        </td>
        <td>
            <pre><code class="language-java">
Path in = Paths.get("input.txt");
Path out = Paths.get("output.txt");
byte[] bytes = Files.readAllBytes(in);
Files.write(out, bytes);
&nbsp
&nbsp
            </code></pre>
        </td>
    <tr>
</table>

### Printing all lines from a file

<table class="table">
    <tr class="row">
        <td>
            <pre><code class="language-java">
String path = "input.txt";
AsyncFiles
    .lines(path)
    .subscribe(new Subscriber<String>() {
        public void onSubscribe(Subscription s) { }
        public void onNext(String line) {
            out.println(line)
        }
        public void onError(Throwable throwable) { }
        public void onComplete() {}
    });
            </code></pre>
        </td>
        <td>
            <pre><code class="language-java">
Path path = Paths.get("input.txt");
Files
    .lines(path)
    .forEach(out::println)
            </code></pre>
        </td>
    <tr>
</table>

The [`AsyncFiles::lines()`](src/main/java/org/javaync/io/AsyncFiles.java#L63)
returns a reactive [`Publisher`](https://www.reactive-streams.org/reactive-streams-1.0.0-javadoc/org/reactivestreams/Publisher.html)
which is compatible with Reactor or RxJava streams. 
Thus we can use their utility methods to easily operate on the result of `AsyncFiles::lines()`.
For example, with the utility methods of Reactor
[`Flux`](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html)
we can rewrite the previous sample as: 

```java
Flux
    .from(AsyncFiles.lines(path))
    .doOnNext(out::println)
    .blockLast(); // block if you want to wait for completion
```
