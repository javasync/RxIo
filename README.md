# AsyncFileRw

[![Build Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3AAsyncFileRw&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.github.javasync%3AAsyncFileRw)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.github.javasync/AsyncFileRw.svg)](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22AsyncFileRw%22)
[![Coverage Status](https://sonarcloud.io/api/project_badges/measure?project=com.github.javasync%3AAsyncFileRw&metric=coverage)](https://sonarcloud.io/component_measures?id=com.github.javasync%3AAsyncFileRw&metric=Coverage)

The `AsyncFiles` class allows Java applications to easily
read/write files asynchronously and without blocking.
This is an equivalent to the standard JDK
[Files](https://docs.oracle.com/javase/10/docs/api/java/nio/file/Files.html)
class but using non-blocking IO.
In section [Usage](#Usage) we present some examples using the `AsyncFiles`
class side by side with the corresponding blocking version of
[Files](https://docs.oracle.com/javase/10/docs/api/java/nio/file/Files.html).

## Installation

First, in order to include it to your Maven project,
simply add this dependency:

To add a dependency on Guava using Maven, use the following:

```xml
<dependency>
    <groupId>com.github.javasync</groupId>
    <artifactId>AsyncFileRw</artifactId>
    <version>1.0.0</version>
</dependency>
```

To add a dependency using Gradle:

```
dependencies {
  compile 'com.github.javasync:AsyncFileRw:1.0.0'
}
```

## Usage



