package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.util.LinkedList;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
	/**
	 * Allocate a new user kernel.
	 */
	public UserKernel() {
		super();
	}

	/**
	 * Initialize this kernel. Creates a synchronized console and sets the
	 * processor's exception handler.
	 */
	public void initialize(String[] args) {
		super.initialize(args);

		int numPhysPages = Machine.processor().getNumPhysPages();
		numFreePages = numPhysPages;
		mutex = new Lock();
		pid_lock = new Lock();
		running_process_lock = new Lock();
		freePages = new LinkedList<Integer>();
		for (int i = 0; i < numPhysPages; i++) {
			freePages.add(i);
		}

		// For testing purpose
		// for(int i = numPhysPages-1; i >= 0; i--) {
		// freePages.add(i);
		// }

		console = new SynchConsole(Machine.console());

		Machine.processor().setExceptionHandler(new Runnable() {
			public void run() {
				exceptionHandler();
			}
		});
	}

	/**
	 * Test the console device.
	 */
	public void selfTest() {
		super.selfTest();

		/*
		 * Skip the console test by default to avoid having to
		 * type 'q' when running Nachos. To use the test,
		 * just remove the return.
		 */
		if (true)
			return;

		System.out.println("Testing the console device. Typed characters");
		System.out.println("will be echoed until q is typed.");

		char c;

		do {
			c = (char) console.readByte(true);
			console.writeByte(c);
		} while (c != 'q');

		System.out.println("");
	}

	/**
	 * Returns the current process.
	 * 
	 * @return the current process, or <tt>null</tt> if no process is current.
	 */
	public static UserProcess currentProcess() {
		if (!(KThread.currentThread() instanceof UThread))
			return null;

		return ((UThread) KThread.currentThread()).process;
	}

	/**
	 * The exception handler. This handler is called by the processor whenever a
	 * user instruction causes a processor exception.
	 * 
	 * <p>
	 * When the exception handler is invoked, interrupts are enabled, and the
	 * processor's cause register contains an integer identifying the cause of
	 * the exception (see the <tt>exceptionZZZ</tt> constants in the
	 * <tt>Processor</tt> class). If the exception involves a bad virtual
	 * address (e.g. page fault, TLB miss, read-only, bus error, or address
	 * error), the processor's BadVAddr register identifies the virtual address
	 * that caused the exception.
	 */
	public void exceptionHandler() {
		Lib.assertTrue(KThread.currentThread() instanceof UThread);

		UserProcess process = ((UThread) KThread.currentThread()).process;
		int cause = Machine.processor().readRegister(Processor.regCause);
		process.handleException(cause);
	}

	/**
	 * Start running user programs, by creating a process and running a shell
	 * program in it. The name of the shell program it must run is returned by
	 * <tt>Machine.getShellProgramName()</tt>.
	 * 
	 * @see nachos.machine.Machine#getShellProgramName
	 */
	public void run() {
		super.run();

		UserProcess process = UserProcess.newUserProcess();

		String shellProgram = Machine.getShellProgramName();
		if (!process.execute(shellProgram, new String[] {})) {
			System.out.println("Could not find executable '" +
					shellProgram + "', trying '" +
					shellProgram + ".coff' instead.");
			shellProgram += ".coff";
			if (!process.execute(shellProgram, new String[] {})) {
				System.out.println("Also could not find '" +
						shellProgram + "', aborting.");
				Lib.assertTrue(false);
			}

		}

		KThread.currentThread().finish();
	}

	public static int allocatePhysPage() {
		if (freePages.isEmpty()) {
			return -1; // fail to allocate new page
		}
		return freePages.removeLast(); // currently remove last to test
	}

	public static int freePhysPage(int physPageNumber) {
		freePages.add(physPageNumber);
		return 0;
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	/** Globally accessible reference to the synchronized console. */
	public static SynchConsole console;

	/** Globally accessible lock to the linked list of free physical pages. */
	public static Lock mutex;

	/** Globally accessible linked list of free physical pages. */
	public static LinkedList<Integer> freePages;

	/** Globally accessible count of free physical pages. */
	public static int numFreePages;

	/** Globally accessible lock of pid_counter. */
	public static Lock pid_lock;

	/** Globally accessible counter for generating PID. */
	public static int pid_counter = 0;

	/** Globally accessible lock of pid_counter. */
	public static Lock running_process_lock;

	/** Globally accessible count of number of running processes. */
	public static int running_process_num = 0;

	// dummy variables to make javac smarter
	private static Coff dummy1 = null;
}
