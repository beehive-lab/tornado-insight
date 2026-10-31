<!-- The Loop Parallel target: inserted into tornadovm-rules.md. -->
## The API: Loop Parallel
The kernel keeps the shape of the original method: its loops stay, and the loops whose
iterations are independent are marked `@Parallel`, so TornadoVM runs their iterations in parallel.

### @Parallel
- `@Parallel` goes on the index variable of a `for` loop, and nowhere else:
  `for (@Parallel int i = 0; i < n; i++)`.
- Never put `@Parallel` on the method, a parameter, a field or any other variable.
- Mark a loop `@Parallel` only if its iterations are independent: no iteration reads a value that
  another iteration writes (for example `a[i] = a[i - 1] + 1` is not independent). Keep such loops
  sequential, or parallelize an inner loop whose iterations are independent.
- Up to three nested loops can be `@Parallel` (1D, 2D, 3D). The loop index is an `int` starting at
  a value, with a `<` or `<=` bound and `i++` or `i += step`.

Wrong:
```java
@Parallel
public static void scale(float[] a, float s) {
    for (int i = 0; i < a.length; i++) {
        a[i] *= s;
    }
}
```
Right:
```java
public static void scale(float[] a, float s) {
    for (@Parallel int i = 0; i < a.length; i++) {
        a[i] *= s;
    }
}
```

### Reductions
- A loop that accumulates into one value (sum, min, max, product) is a reduction. The result goes
  into element 0 of an array parameter annotated `@Reduce`, and the loop is `@Parallel`.
- Initialise the result with the identity of the operation (0 for a sum) before the loop, as the
  original method does.

```java
public static void sum(float[] input, @Reduce float[] result) {
    result[0] = 0.0f;
    for (@Parallel int i = 0; i < input.length; i++) {
        result[0] += input[i];
    }
}
```

### Examples
Vector addition:
```java
public static void vectorAdd(float[] a, float[] b, float[] c) {
    for (@Parallel int i = 0; i < c.length; i++) {
        c[i] = a[i] + b[i];
    }
}
```

Matrix multiplication with two parallel loops (the inner `k` loop stays sequential):
```java
public static void matMul(float[] a, float[] b, float[] c, int n) {
    for (@Parallel int i = 0; i < n; i++) {
        for (@Parallel int j = 0; j < n; j++) {
            float sum = 0.0f;
            for (int k = 0; k < n; k++) {
                sum += a[i * n + k] * b[k * n + j];
            }
            c[i * n + j] = sum;
        }
    }
}
```

A method that returns a value, `static float dot(float[] x, float[] y)`, becomes:
```java
public static void dot(float[] x, float[] y, @Reduce float[] result) {
    result[0] = 0.0f;
    for (@Parallel int i = 0; i < x.length; i++) {
        result[0] += x[i] * y[i];
    }
}
```
