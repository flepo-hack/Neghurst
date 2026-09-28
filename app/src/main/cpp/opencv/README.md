# Optional: the OpenCV Android SDK

The core engine in `rendera_core.cpp` is complete and has no external
dependencies. This directory is where you can drop the **OpenCV Android SDK** to
turn on the accelerated kernels in `rendera_opencv.cpp` (`cv::phaseCorrelate` and
a fused `warpAffine` + `absdiff`), which the build picks up automatically.

## Getting the SDK

```
curl -L -o opencv.zip \
  https://github.com/opencv/opencv/releases/download/4.10.0/opencv-4.10.0-android-sdk.zip
unzip opencv.zip
# you now have opencv-4.10.0-android-sdk/ with sdk/jni/ inside
```

Point the build at it either way:

```
./gradlew assembleRelease -PRENDERA_OPENCV_SDK=$PWD/opencv-4.10.0-android-sdk
# or
export RENDERA_OPENCV_SDK=$PWD/opencv-4.10.0-android-sdk
```

or unpack it here as `app/src/main/cpp/opencv/`, which the build finds with no
arguments at all. This file is the directory's only tracked content, so unpacking
into it cannot dirty the repository.

## What is and is not accelerated

Accelerated: the windowed phase correlation and the motion compensated absolute
difference, which are per pixel work over the whole grid and are what OpenCV is
genuinely good at.

Not accelerated, on purpose: the Kalman recursion, the closest point of approach
solve and the escape heading scoring. Those run over at most sixteen objects, are
numerically delicate, and are the code the unit tests pin down. Handing them to
OpenCV would put a second, differently rounded copy of the maths in the build,
and the two would slowly disagree. The clear/defer verdict and the work rate
would become untrustworthy, which defeats the point of the learning log.

## The `org.opencv:opencv` AAR will not work here

That artifact is about 70 MB and exports **only** its JNI bridge: OpenCV's Android
builds are compiled with `-fvisibility=hidden`, so `cv::` symbols are not
available to this NDK build no matter how the include paths are set. It would add
70 MB to the APK and still leave `cv::` unreachable. See
`docs/VERIFICATION.md` section 6.
