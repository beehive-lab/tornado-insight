<!-- The Kernel API target: inserted into tornadovm-rules.md. Its examples are tested on a GPU. -->
## The API: Kernel API
The kernel is written like a GPU kernel: TornadoVM launches one thread per point of a grid, and
each thread computes its own part of the result from its thread index.

### The method
- Add a first parameter `KernelContext context`, before the original parameters. The original
  parameters keep their types, names and order.
- Do not use `@Parallel` or `@Reduce`.
- Start the method body with a comment that says how many threads to launch in each dimension,
  as Java `int` expressions over the parameters: `// grid: x.length` for one dimension,
  `// grid: n, n` for two, at most three. TornadoVM launches exactly these threads.
- Optionally, the next line gives the work-group size per dimension: `// local: 256`. The grid
  is then rounded up to a multiple of it, so some threads have no element: guard every access,
  for example `if (i < x.length)`. Give a work-group size when you use local memory or barriers.

### Threads
- `context.globalIdx`, `context.globalIdy`, `context.globalIdz`: the thread's index in the grid.
- `context.localIdx` (and `localIdy`, `localIdz`): its index in its work-group;
  `context.groupIdx`: the work-group's index; `context.localGroupSizeX`: the work-group size.
- Replace the loop over the elements by the thread index; do not loop over all elements in
  every thread. A loop that is not parallel (for example the `k` loop of a matrix product) stays
  inside the thread.
- Local memory, shared by the threads of a work-group:
  `float[] partial = context.allocateFloatLocalArray(256);` (also `Int`, `Long`, `Double`), with a
  constant size equal to the work-group size. `context.localBarrier()` waits until every thread
  of the work-group reaches it; every thread of the group must reach the same barriers.
- Atomic update of an element: `context.atomicAdd(array, index, value)` for `int[]`,
  `IntArray`, `LongArray`, `FloatArray` and `DoubleArray`.

### Reductions
A method that returns one value (sum, minimum, maximum) gets a last parameter for the result, a
`FloatArray`, `IntArray`, `LongArray` or `DoubleArray` of length 1 that the caller sets to the
identity (0 for a sum). Each work-group reduces its elements in local memory, and its first
thread adds the group's value with `atomicAdd`.

### Examples
Vector addition:
```java
public static void vectorAdd(KernelContext context, float[] a, float[] b, float[] c) {
    // grid: c.length
    int i = context.globalIdx;
    c[i] = a[i] + b[i];
}
```

Matrix multiplication on a 2D grid (the `k` loop stays inside each thread):
```java
public static void matMul(KernelContext context, float[] a, float[] b, float[] c, int n) {
    // grid: n, n
    int i = context.globalIdx;
    int j = context.globalIdy;
    float sum = 0.0f;
    for (int k = 0; k < n; k++) {
        sum += a[i * n + k] * b[k * n + j];
    }
    c[i * n + j] = sum;
}
```

A method that returns a value, `static float sum(float[] input)`, becomes:
```java
public static void sum(KernelContext context, float[] input, FloatArray result) {
    // grid: input.length
    // local: 256
    float[] partial = context.allocateFloatLocalArray(256);
    int i = context.globalIdx;
    int local = context.localIdx;
    partial[local] = i < input.length ? input[i] : 0.0f;
    for (int stride = 128; stride > 0; stride /= 2) {
        context.localBarrier();
        if (local < stride) {
            partial[local] += partial[local + stride];
        }
    }
    if (local == 0) {
        context.atomicAdd(result, 0, partial[0]);
    }
}
```
