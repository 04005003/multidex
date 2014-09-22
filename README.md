`android.support.multidex`
--------------------------

Library Project including compatibility multi dex loader.

This can be used by an Android project to install classloader
with multiple dex of applications running on API 4+.

### What's this?

This is just a fork of the original [multidex](https://android.googlesource.com/platform/frameworks/multidex/)
repo. I'll maintain this for a while since I needed this to be in a Maven
repo for easy consumption.

### What's it for again?

While dexing classes it is sometimes possible to exceed the maximum (65536) methods
limit (try using Google Play Services and Scaloid for instance):

```
trouble writing output: Too many method references: 70820; max is 65536.
You may try using --multi-dex option.
```

So the suggestion is to use the `--multi-dex` option of the `dx` utility; this
will generate several dex files (`classes.dex`, `classes2.dex`, etc.) that will
be included in the APK. Since the `dx` utility is not currently configurable
from the Android plugin for Gradle you will have to add this options manually
to the dx script (e.g. edit `$ANDROID_HOME/build-tools/19.1.0/dx`; in my case
the last line looks like this: `exec java $javaOpts -jar "$jarpath" --multi-dex "$@"`)

By default Dalvik's classloader will look for the `classes.dex` file only, so
it's necessary to patch it so that it can read from multiple dex files. That's
what this project provides.

### Usage

Add this project to your classpath:

```groovy
repositories {
  jcenter()
}

dependencies {
  compile 'com.google.android:multidex:0.1'
}
```

Then you have 3 possibilities:

- Declare `android.support.multidex.MultiDexApplication` as the application in
your `AndroidManifest.xml`
- Have your `Application` extends `android.support.multidex.MultiDexApplication`, or...
- Have your `Application` override `attachBaseContext` starting with:

```java
import android.support.multidex.MultiDex;

// ...

@Override
protected void attachBaseContext(Context base) {
  super.attachBaseContext(base);
  MultiDex.install(this);
```

If you are unlucky enough, the multidex classes will not be included in the
`classes.dex` file (the first one read by the classloader), which in turn
will render all this useless. There's a workaround for this though. Create a file
with this content:

```
android/support/multidex/BuildConfig.class
android/support/multidex/MultiDex$V14.class
android/support/multidex/MultiDex$V19.class
android/support/multidex/MultiDex$V4.class
android/support/multidex/MultiDex.class
android/support/multidex/MultiDexApplication.class
android/support/multidex/MultiDexExtractor$1.class
android/support/multidex/MultiDexExtractor.class
android/support/multidex/ZipUtil$CentralDirectory.class
android/support/multidex/ZipUtil.class
```

And pass the path of this file to the `--main-dex-list` option of the `dx` utility.

Since the `dx` utility is not currently configurable from the Android plugin for
Gradle you will have to add this options manually to the dx script (e.g.
edit `$ANDROID_HOME/build-tools/19.1.0/dx`)

### `build.gradle` example

```groovy
buildscript {
    repositories {
        mavenCentral()
        maven {
            url 'http://saturday06.github.io/gradle-android-scala-plugin/repository/snapshot'
        }
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:0.12.2'
        classpath 'jp.leafytree.gradle:gradle-android-scala-plugin:1.0-SNAPSHOT'
    }
}

apply plugin: 'com.android.application'
apply plugin: 'android-scala'

repositories {
    mavenCentral()
    jcenter()
}

android {
    compileSdkVersion 19
    buildToolsVersion '20' // tested on 19.x family too

    defaultConfig {
        applicationId 'some.app'
        minSdkVersion 19
        targetSdkVersion 19
        versionCode 1
        versionName '1.0'
    }
}

dependencies {
    compile 'com.google.android:multidex:0.1'
    compile 'com.android.support:support-v4:19.0.1'
    compile 'com.google.android.gms:play-services:5.0.77'
    compile 'org.scala-lang:scala-library:2.11.2'
    compile 'org.scaloid:scaloid_2.11:3.4-10'
}
```
