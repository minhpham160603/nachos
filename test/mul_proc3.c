#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main(){
    int p2 = exec("matmult.coff", 0, 0);
    // int p1 = exec("write10.coff", 0, 0);
    int p3 = exec("sort.coff", 0, 0);
    exit(69);
}
