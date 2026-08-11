a = 1
StringBuilder sb = new StringBuilder();
try {
    a++;
    try {
        a++;
        try {
            a++;
            try {
                a++;
                sb.append('a: ' + a++ + ', ');
                return [a, sb];
            } finally {
                sb.append('b: ' + a++ + ', ');
                return [a, sb];
            }
        } finally {
            sb.append('c: ' + a++ + ', ');
            return [a, sb];
        }
    } finally {
        sb.append('d: ' + a++ + ', ');
        return [a, sb];
    }
} finally {
    sb.append('e: ' + a++ + ', ');
    return [a, sb];
}
