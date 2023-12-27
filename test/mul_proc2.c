#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main(){
    int p2 = exec("write10.coff", 0, 0);
    int p1 = exec("write11.coff", 0, 0);
    int p3 = exec("write12.coff", 0, 0);
    int p4 = exec("write13.coff", 0, 0);
    int p5 = exec("write14.coff", 0, 0);
    exit(69);
}
