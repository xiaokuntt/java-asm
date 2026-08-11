var doubleAsync = value => async (value) => value * 2;
var first = doubleAsync(10);
var second = async () => 22;
return await first + await second;
