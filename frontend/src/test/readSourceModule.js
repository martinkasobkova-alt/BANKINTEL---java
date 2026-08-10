const sourceModules = import.meta.glob("/src/**/*.jsx", {
  eager: true,
  query: "?raw",
  import: "default",
});

export function readSourceModuleByBasename(filename) {
  const suffix = `/${filename}`;
  const matches = Object.entries(sourceModules).filter(([modulePath]) => modulePath.endsWith(suffix));

  if (matches.length !== 1) {
    throw new Error(`Expected one source module named ${filename}, found ${matches.length}.`);
  }

  return matches[0][1];
}
