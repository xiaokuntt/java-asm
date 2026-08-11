a = 1
StringBuilder sb = new StringBuilder();
try {
    a++;
    try {
        a++;
        return [a, sb];
    } finally {
        sb.append('a: ' + a++ + ', ');
    }
} finally {
    sb.append('b: ' + a++ + ', ');
}
