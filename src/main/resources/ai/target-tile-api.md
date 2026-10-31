<!-- The Tile API target: inserted into tornadovm-rules.md. Its examples are tested on a GPU with CUDA Tile. -->
## The API: Tile API
The kernel works on tiles: fixed-size blocks of elements that a whole block of the GPU loads,
computes and stores at once (CUDA Tile). TornadoVM launches one tile block per point of a grid.
The Tile API runs only on NVIDIA GPUs, through TornadoVM's CUDA backend; there is no OpenCL or
SPIR-V version. The thread mapping inside a tile block belongs to CUDA Tile, so the kernel never
uses thread indexes, work-groups, local memory or barriers.

### The method
- Add a first parameter `TileContext tc`, before the original parameters. The original
  parameters keep their types, names and order. Tiles work on TornadoVM arrays (`FloatArray`,
  `IntArray`, `DoubleArray`, `HalfFloatArray`), not on Java arrays.
- Do not use `@Parallel` or `@Reduce`, and do not loop over the elements: tile operations
  compute a whole tile.
- Start the method body with a comment that says how many tile blocks to launch in each
  dimension, as Java `int` expressions over the parameters, for example
  `// grid: (x.getSize() + 255) / 256` for tiles of 256 elements. Do not give a `// local:` size.

### Tiles
- `PartitionView xv = tc.partition(tc.view(x, x.getSize()), 256);` splits an array into tiles of
  256 elements (2D: `tc.view(a, rows, columns)` and `tc.partition(view, 32, 32)`). Tile sizes are
  integer literals, powers of two.
- `int block = tc.bidX();` is the tile block's index (`bidY()`, `bidZ()` for 2D and 3D grids).
- `Tile t = xv.loadMasked(block);` loads a tile, and `yv.storeMasked(t, block);` stores one; the
  masked forms handle a last tile that is only partly inside the array, so use them unless the
  length is a multiple of the tile size.
- Element-wise operations return new tiles: `tc.add(a, b)`, `tc.sub`, `tc.mul`, `tc.div`,
  `tc.fma(a, b, c)`, `tc.scale(a, s)` (times a scalar), `tc.maximum`, `tc.minimum`, `tc.exp`,
  `tc.sqrt`, `tc.abs` and more; `tc.full(DType.F32, value, 256)` is a tile of one value.
- Reductions over a dimension: `tc.sum(t, 0)`, `tc.max(t, 0)`, `tc.min(t, 0)`.

### Reductions
A method that returns one value gets a last parameter for the result, a `FloatArray`,
`IntArray` or `DoubleArray` of length 1 that the caller sets to 0. Each tile block reduces its
tile and adds it to the result with an atomic add.

### Examples
SAXPY on any length:
```java
public static void saxpy(TileContext tc, float a, FloatArray x, FloatArray y) {
    // grid: (x.getSize() + 255) / 256
    PartitionView xv = tc.partition(tc.view(x, x.getSize()), 256);
    PartitionView yv = tc.partition(tc.view(y, y.getSize()), 256);
    int block = tc.bidX();
    yv.storeMasked(tc.add(tc.scale(xv.loadMasked(block), a), yv.loadMasked(block)), block);
}
```

A method that returns a value, `static float sum(FloatArray input)`, becomes:
```java
public static void sum(TileContext tc, FloatArray input, FloatArray result) {
    // grid: (input.getSize() + 255) / 256
    PartitionView in = tc.partition(tc.view(input, input.getSize()), 256);
    PartitionView out = tc.partition(tc.view(result, 1), 1);
    Tile partial = tc.sum(in.loadMasked(tc.bidX()), 0);
    out.atomicAdd(tc.reshape(partial, 1), 0);
}
```
