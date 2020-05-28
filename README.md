# RxIo

[![Build Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3AAsyncFileRw&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.github.javasync%3AAsyncFileRw)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.github.javasync/RxIo.svg)](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22RxIo%22)
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

First, in order to include it to your project,
simply add this dependency:

<table>
<tr>
<th>Maven</th>
<th>Gradle</th>
</tr>
<tr>
<td>

```xml
<dependency> 
    <groupId>com.github.javasync</groupId>
    <artifactId>RxIo</artifactId>
    <version>1.1.4</version>
</dependency>
```

</td>
<td>

```groovy
dependencies {
  implementation 'com.github.javasync:RxIo:1.1.4'
}
```

</td>
</tr>
<table>

## Usage

<table>
<tr>
<td>

```java

Path path = Paths.get("output.txt")
List<String> data = asList("super", "brave", "isel", "gain");
AsyncFiles
    .write(path, data)
    .join(); // block if you want to wait for write completion
```

</td>
<td>

```java
/**
 *  Writing lines into a file
 */
Path path = Paths.get("output.txt")
List<String> data = asList("super", "brave", "isel", "gain");
Files.write(path, data);
```

</td>
</tr>
<tr>
<td>

```java
Path in = Paths.get("input.txt");
Path out = Paths.get("output.txt");
AsyncFiles
        .readAllBytes(in)
        .thenCompose(bytes -> AsyncFiles.writeBytes(out, bytes))
        .join(); // block if you want to wait for write completion
```

</td>
<td>

```java
/**
 * Copying from one file to another.
 */
Path in = Paths.get("input.txt");
Path out = Paths.get("output.txt");
byte[] bytes = Files.readAllBytes(in);
Files.write(out, bytes);
```

</td>
</tr>
<tr>
<td>

```java
/**
 * Printing all lines from a file
 */
String path = "input.txt";
AsyncFiles
    .lines(path)
    .subscribe(doOnNext(out::println));
```
    
</td>
<td>

```java



Path path = Paths.get("input.txt");
Files
    .lines(path)
    .forEach(out::println)
```

</td>
</tr>
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
