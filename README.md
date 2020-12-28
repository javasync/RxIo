# RxIo

[![Build Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3ARxIo&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.github.javasync%3ARxIo)
[![Coverage Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3ARxIo&metric=coverage)](https://sonarcloud.io/dashboard?id=com.github.javasync%3ARxIo)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.github.javasync/RxIo.svg)](https://search.maven.org/artifact/com.github.javasync/RxIo)

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
    <version>1.2.1</version>
</dependency>
```

</td>
<td>

```groovy
implementation 'com.github.javasync:RxIo:1.2.1'
```

</td>
</tr>
<table>

## Usage

<table>
<tr>
<td>

```java
String path = "input.txt";
AsyncFiles
  .asyncQuery(path) // printing all lines from input.txt
  .subscribe((line, err) -> out.println(line)) // lack check err
  .join(); // block if you want to wait for completion
```
    
</td>
<td>

```java
Path path = Paths.get("input.txt");
Files
  .lines(path) // printing all lines from input.txt
  .forEach(out::println)
```

</td>
</tr>
<tr>
<td>

```java

Path path = Paths.get("output.txt")
List<String> data = asList("super", "brave", "isel", "gain");
AsyncFiles
  .write(path, data) // writing lines to output.txt
  .join(); // block if you want to wait for completion
```

</td>
<td>

```java
/**
 *  Writing lines to output.txt
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
  .join(); // block if you want to wait for completion
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
</table>

The [`AsyncFiles::asyncQuery()`](src/main/java/org/javaync/io/AsyncFiles.java#L60)
returns an `AsyncQuery` that allows asynchronous subscription and chaining intermediate operations
such as `filter`, `map` and others.
In the following example we show how to print all words of a gutenberg.org file content without repetitions:
```java
AsyncFiles
    .asyncQuery(file)
    .filter(line -> !line.isEmpty())                   // Skip empty lines
    .skip(14)                                          // Skip gutenberg header
    .takeWhile(line -> !line.contains("*** END OF "))  // Skip gutenberg footnote
    .flatMapMerge(line -> AsyncQuery.of(line.split("\\W+")))
    .distinct()
    .subscribe((word, err) -> {
        if(err != null) err.printStackTrace();
        else out.println(word);
    })
    .join(); // block if you want to wait for completion
```

Alternatively, the [`AsyncFiles::lines()`](src/main/java/org/javaync/io/AsyncFiles.java#L84)
returns a reactive [`Publisher`](https://www.reactive-streams.org/reactive-streams-1.0.0-javadoc/org/reactivestreams/Publisher.html)
which is compatible with Reactor or RxJava streams. 
Thus we can use their utility methods to easily operate on the result of `AsyncFiles::lines()`.
For example, with the utility methods of Reactor
[`Flux`](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html)
we can rewrite the previous sample as: 

```java
Flux
    .from(AsyncFiles.lines(file))
    .filter(line -> !line.isEmpty())                   // Skip empty lines
    .skip(14)                                          // Skip gutenberg header
    .takeWhile(line -> !line.contains("*** END OF "))  // Skip gutenberg footnote
    .flatMap(line -> Flux.fromArray(line.split("\\W+")))
    .distinct()
    .doOnNext(word -> count[0]++ )
    .doOnNext(out::println)
    .doOnError(Throwable::printStackTrace)
    .blockLast(); // block if you want to wait for completion
```
