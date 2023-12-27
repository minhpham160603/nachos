#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main(){
    int p2 = exec("swap5.coff", 0, 0);
    int p1 = exec("swap4.coff", 0, 0);
    int p3 = exec("swap4.coff", 0, 0);
    int p4 = exec("swap5.coff", 0, 0);
    int p5 = exec("swap4.coff", 0, 0);
    exit(69);
}
