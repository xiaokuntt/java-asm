return {
    'byteMax1': 127b,               // ok
    'byteMax2': Byte.MAX_VALUE,     // ok
    'byteMax3': (127b+1),           // ok, output 128   why?
    'byteMax4': (127b+1b),          // ok, output 128   why?
    'byteMin1': -127b,              // ok
    'byteMin2': Byte.MIN_VALUE,     // ok, output -128
    'byteMin3': -128b,              // error, why?
    'byteMin4': (-127b - 1),        // ok, output -128
    'byteMin5': (-127b - 2),        // ok, output -129  , why?
    'byteMin6': (-127b - 2b),       // ok, output -129  , why?
    'byteMin7': (-127b - Integer.MIN_VALUE),       // ok, output 2147483521  , why use integer?
    'byteZero1': 0b,                // error
    'byteZero2': -0b,               // error
    'byteZero3': 0B,                // error
    'byteZero4': -0B,               // error
    'byteZero5': (1b -1),           // ok, output 0
    'shortMax1': 32767s,            // ok
    'shortMax2': Short.MAX_VALUE,   // ok
    'shortMax3': (32767s +1),       // ok, ouput 32768
//  'shortMax4': 32768s ,           // error
    'shortMin1': -32767s,           // ok
    'shortMin2': Short.MIN_VALUE,   // ok, output -32768
    'shortMin3': -32768s,           // error
    'shortMin4': (-32767s - 1),     // ok, output -32768 , why?
    'shortMin5': (-32767s - 2),     // ok, output -32769 , why?
    'shortMin6': (-32767s - 2s),    // ok, output -32769 , why?
    'shortMin7': (-32767s - 32767s),// ok, output -65534 , why?
    'shortMin8': (32767s - Integer.MIN_VALUE), // ok, output -2147450881 , why use integer?
    'shortZero1': 0s ,              // ok
    'shortZero2':-0s ,              // ok
    'shortZero3': 0S ,              // ok
    'shortZero4':-0S ,              // ok

    'intMax1': 2147483647 ,         // ok
    'intMax2': Integer.MAX_VALUE  , // ok
    'intMax3': 2147483647 + 1,      // ok, output -2147483648 , fine
//  'intMax4': 2147483648,          // error , fine
    'intMin1': -2147483647 ,        // ok
    'intMin2': Integer.MIN_VALUE  , // ok
    'intMin3': -2147483647 - 1,     // ok, output -2147483648 , fine
//  'intMax4': 2147483648,          // error , fine
    'intMin5': -2147483647 - 2,     // ok, output 2147483647 , fine
    'intMin6': -2147483648
}
