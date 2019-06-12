The putIfAbsent method is typically used to ensure that a single value is associated with a given key (the first value for which put if absent succeeds). If you ignore the return value and retain a reference to the value passed in, you run the risk of retaining a value that is not the one that is associated with the key in the map. If it matters which one you use and you use the one that isn't stored in the map, your program will behave incorrectly.