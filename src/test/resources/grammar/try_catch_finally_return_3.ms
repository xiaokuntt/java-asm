a = 1
s = ''
try {
    b= a++ / 0
} catch(e) {
    a++;
    s = s + 'a: ' + a + ' ' + e + ', ';
    c = a++ / 0
} finally {
    s = s + 'b: ' + a++ + ', ';
    return [a, s]
}