package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.LinkedList;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
		swapFileFreeList = new LinkedList<Integer>();
		// ppn to process
		ppnToProcessTable = new VMProcess[Machine.processor().getNumPhysPages()];
		pinTable = new int[Machine.processor().getNumPhysPages()];
		for (int i = 0; i < Machine.processor().getNumPhysPages(); i++){
			pinTable[i] = 0;
		}
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		swapFile = Machine.stubFileSystem().open("swapfs", true);
		vmMutex = new Lock();
		swapFreeLock = new Lock();
		ppnToProcessLock = new Lock();
		pinCondition = new Condition(vmMutex);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	public static int allocateDiskPage() {
		swapFreeLock.acquire();
		if (!swapFileFreeList.isEmpty()) {
			swapFreeLock.release();
			return swapFileFreeList.removeFirst();
		} else {
			currentListSize += 1;
			swapFreeLock.release();
			return currentListSize - 1;
		}
	}

	public static void freeDiskPage(int spn) {
		swapFreeLock.acquire();
		swapFileFreeList.add(spn);
		swapFreeLock.release();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';


	public static OpenFile swapFile;
	private static int currentListSize = 0;
	
	public static Lock swapFreeLock;
	private static LinkedList<Integer> swapFileFreeList;
	public static VMProcess[] ppnToProcessTable;

	public static Lock vmMutex;
	public static Lock ppnToProcessLock;

	public static int[] pinTable;
	public static Condition pinCondition;
}
