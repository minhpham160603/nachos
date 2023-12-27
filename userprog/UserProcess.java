package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		// int numPhysPages = Machine.processor().getNumPhysPages();
		// pageTable = new TranslationEntry[numPhysPages];
		// for (int i = 0; i < numPhysPages; i++)
		// pageTable[i] = new TranslationEntry(i, i, true, false, false, false);

		// initialize stdin and stdout
		fdTable[0] = UserKernel.console.openForReading();
		fdTable[1] = UserKernel.console.openForWriting();

		UserKernel.pid_lock.acquire();
		this.pid = UserKernel.pid_counter;
		UserKernel.pid_counter++;
		UserKernel.pid_lock.release();

		children_by_pid = new HashMap<Integer, UserProcess>();
		children_exit_status = new HashMap<Integer, Integer>();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		UserKernel.running_process_lock.acquire();
		UserKernel.running_process_num++;
		UserKernel.running_process_lock.release();

		String name = Machine.getProcessClassName();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader. Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals("nachos.userprog.UserProcess")) {
			return new UserProcess();
		} else if (name.equals("nachos.vm.VMProcess")) {
			return new VMProcess();
		} else {
			return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);
		thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr     the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 *                  including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 *         found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data  the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr  the first byte of virtual memory to read.
	 * @param data   the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 *               array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		int amount = 0;
		byte[] memory = Machine.processor().getMemory();

		if (vaddr < 0 || vaddr >= pageSize * numPages)
			return 0;

		int vpn = Processor.pageFromAddress(vaddr);
		if (vpn >= numPages)
			return 0;

		int offsetInPage = Processor.offsetFromAddress(vaddr);// the size of page head to the first byte of vaddr
		int ppn = pageTable[vpn].ppn;
		int startAt = Processor.makeAddress(ppn, offsetInPage); // physical starting address

		// read Head
		if (offsetInPage + length <= pageSize) {
			System.arraycopy(memory, startAt, data, offset, length);
			return length;
		} else {
			System.arraycopy(memory, startAt, data, offset, (pageSize - offsetInPage));
			amount += pageSize - offsetInPage;
		}

		// read Middle
		int numPageInMiddle = (int) Math.ceil(((double) (offsetInPage + length)) / pageSize) - 2; // exclude head and
																									// tail
		int i;
		for (i = vpn + 1; i < vpn + numPageInMiddle + 1; i++) {
			if (i >= numPages)
				return amount;
			ppn = pageTable[i].ppn;
			int paddr = Processor.makeAddress(ppn, 0);
			System.arraycopy(memory, paddr, data, offset + amount, pageSize);
			amount += pageSize;
		}

		// read Tail
		if (i >= numPages)
			return amount;
		int dataLeft = length - amount;
		if (dataLeft == 0)
			return amount;
		if (dataLeft > pageSize)
			return -1; // for debug, this should not happen
		ppn = pageTable[i].ppn;
		int paddr = Processor.makeAddress(ppn, 0);
		System.arraycopy(memory, paddr, data, offset + amount, dataLeft);
		amount += dataLeft;
		return amount;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data  the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr  the first byte of virtual memory to write.
	 * @param data   the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 *               memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		// Do we transfer part of data or just return 0 when vaddr + length >= pageSize
		// * numPages? A: check in handleRead
		if (vaddr < 0 || vaddr >= pageSize * numPages)
			return 0;

		int vpn = Processor.pageFromAddress(vaddr);
		if (vpn >= numPages)
			return 0;
		if (pageTable[vpn].readOnly)
			return 0;

		int offsetInPage = Processor.offsetFromAddress(vaddr);

		int ppn = pageTable[vpn].ppn;
		int startAt = Processor.makeAddress(ppn, offsetInPage); // physical starting address

		// split things to write into 3 parts: Head, Middle, Tail

		// write Head
		int amount = 0;
		if (offsetInPage + length <= pageSize) {
			System.arraycopy(data, offset, memory, startAt, length);
			return length;
		} else {
			System.arraycopy(data, offset, memory, startAt, (pageSize - offsetInPage));
			amount += pageSize - offsetInPage;
		}

		// write Middle
		int numPageInMiddle = (int) Math.ceil(((double) (offsetInPage + length)) / pageSize) - 2; // exclude head and
																									// tail
		int i;
		for (i = vpn + 1; i < vpn + numPageInMiddle + 1; i++) {
			if (i >= numPages)
				return amount;
			if (pageTable[i].readOnly)
				return amount;
			ppn = pageTable[i].ppn;
			int paddr = Processor.makeAddress(ppn, 0);
			System.arraycopy(data, offset + amount, memory, paddr, pageSize);
			amount += pageSize;
		}

		// write Tail
		if (i >= numPages)
			return amount;
		if (pageTable[i].readOnly)
			return amount;
		int dataLeft = length - amount;
		if (dataLeft == 0)
			return amount;
		if (dataLeft > pageSize)
			return -1; // for debug, this should not happen
		ppn = pageTable[i].ppn;
		int paddr = Processor.makeAddress(ppn, 0);
		System.arraycopy(data, offset + amount, memory, paddr, dataLeft);
		amount += dataLeft;
		return amount;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		} catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
			// System.out.println(numPages);
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		stackStartPage = numPages;

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		Lib.debug(dbgProcess, "UserProcess.load: " + numPages + " pages in address space ("
				+ Machine.processor().getNumPhysPages() + " physical pages)");

		/*
		 * Layout of the Nachos user process address space.
		 * The code above calculates the total number of pages
		 * in the address space for this executable.
		 *
		 * +------------------+
		 * | Code and data |
		 * | pages from | size = num pages in COFF file
		 * | executable file |
		 * | (COFF file) |
		 * +------------------+
		 * | Stack pages | size = stackPages
		 * +------------------+
		 * | Arg page | size = 1
		 * +------------------+
		 *
		 * Page 0 is at the top, and the last page at the
		 * bottom is the arg page at numPages-1.
		 */

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		// use mutex to protect all UserKernel fields
		UserKernel.mutex.acquire();
		if (numPages > UserKernel.numFreePages) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			UserKernel.mutex.release();
			return false;
		}

		pageTable = new TranslationEntry[numPages];
		for (int vpn = 0; vpn < numPages; vpn++) {
			boolean readOnly = false;
			if (vpn < coff.getNumSections()) {
				readOnly = coff.getSection(vpn).isReadOnly();
			}
			int ppn = UserKernel.freePages.removeFirst();
			UserKernel.numFreePages -= 1;
			// TranslationEntry(int vpn, int ppn, boolean valid, boolean readOnly, boolean
			// used, boolean dirty)
			pageTable[vpn] = new TranslationEntry(vpn, ppn, true, readOnly, false, false);
		}
		UserKernel.mutex.release();

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				int ppn = pageTable[vpn].ppn;
				section.loadPage(i, ppn);
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		UserKernel.mutex.acquire();
		for (int vpn = 0; vpn < numPages; vpn++) {
			int ppn = pageTable[vpn].ppn;
			UserKernel.freePhysPage(ppn);
		}
		UserKernel.mutex.release();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		Lib.debug(dbgProcess, "UserProcess.handleHalt");
		if (pid != 0) {
			return -1;
		}

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	// edge cases remaining: 1. when creat is called multiple times on the same
	// filename.
	private int handleCreate(int vaddr) { // vaddr for filename
		Lib.debug(dbgProcess, "UserProcess.handleCreate " + String.valueOf(vaddr));

		// already bullet-proof against null pointer and pointer out of bound in
		// readVirtualMemory
		String fileName = readVirtualMemoryString(vaddr, 256);

		if (fileName == null) {
			return -1; // failed if filename string doesn't have a null terminator
		}

		OpenFile openFile = ThreadedKernel.fileSystem.open(fileName, true); // true for truncate

		if (openFile == null) {
			return -1; // failed open file from StubFileSystem.open
		}

		for (int i = 2; i < fdTable.length; i++) {
			if (fdTable[i] == null) {
				Lib.debug(dbgProcess, "New file descriptor put at " + String.valueOf(i));
				fdTable[i] = openFile;
				return i; // Returns the new file descriptor
			}
		}
		return -1; // no vacant place in fdTable
	}

	// I think the only difference between create and open is that open doesn't do
	// truncate
	private int handleOpen(int vaddr) { // vaddr for filename
		Lib.debug(dbgProcess, "UserProcess.handleOpen " + String.valueOf(vaddr));

		// already bullet-proof against null pointer and pointer out of bound in
		// readVirtualMemory
		String fileName = readVirtualMemoryString(vaddr, 256);

		if (fileName == null) {
			Lib.debug(dbgProcess, "UserProcess.handleOpen failed because no null terminator");
			return -1; // failed if filename string doesn't have a null terminator
		}

		OpenFile openFile = ThreadedKernel.fileSystem.open(fileName, false); // false for not truncate
		if (openFile == null) {
			Lib.debug(dbgProcess, "UserProcess.handleOpen failed to open file");
			return -1; // failed open file from StubFileSystem.open
		}

		for (int i = 2; i < fdTable.length; i++) {
			if (fdTable[i] == null) {
				Lib.debug(dbgProcess, "New file descriptor put at " + String.valueOf(i));
				fdTable[i] = openFile;
				return i; // Returns the new file descriptor
			}
		}
		Lib.debug(dbgProcess, "UserProcess.handleOpen failed because no vacant place in fdTable");
		return -1; // no vacant place in fdTable
	}

	private int handleRead(int fd, int vaddr, int size) {
		Lib.debug(dbgProcess, "UserProcess.handleRead fd: " + fd + " vaddr: " + vaddr + " size: " + size);
		if (size < 0)
			return -1;
		// If part of the buffer is invalid
		if (vaddr < 0 || vaddr + size >= pageSize * numPages)
			return -1;
		if (fd < 0 || fd > 15 || fdTable[fd] == null)
			return -1;
		if (size == 0)
			return 0;

		int bytesRead = 0;
		while (size > 0) {
			int bufferSize = Math.min(pageSize, size);
			byte[] buffer = new byte[bufferSize];
			int readNum = fdTable[fd].read(buffer, 0, bufferSize);

			if (readNum < 0)
				return -1;
			if (readNum == 0)
				return bytesRead;

			int writeNum = writeVirtualMemory(vaddr, buffer, 0, readNum);
			if (writeNum < readNum) {
				Lib.debug(dbgProcess, "Part of buffer written to is read-only.");
				return -1;
			}

			// readNum < bufferSize means we read to the end of file.
			if (readNum < bufferSize) {
				Lib.debug(dbgProcess, "Read to the end of file.");
				bytesRead += writeNum;
				break;
			}
			bytesRead += writeNum;
			size -= bufferSize;
			vaddr += bufferSize;
		}

		// Lib.debug(dbgProcess, readVirtualMemoryString(vaddr, size));
		return bytesRead;
	}

	private int handleWrite(int fd, int vaddr, int size) {
		Lib.debug(dbgProcess, "UserProcess.handleWrite fd: " + fd + " vaddr: " + vaddr + " size: " + size);
		if (size < 0)
			return -1;
		if (vaddr < 0 || vaddr + size >= pageSize * numPages)
			return -1;
		if (fd < 0 || fd > 15 || fdTable[fd] == null)
			return -1;
		if (size == 0)
			return 0;

		int bytesWritten = 0;
		// System.out.println("in handlewrite");
		while (size > 0) {
			int bufferSize = Math.min(pageSize, size);
			byte[] buffer = new byte[bufferSize];

			int readNum = readVirtualMemory(vaddr, buffer, 0, bufferSize);
			if (readNum < 0)
				return -1;

			int writeNum = fdTable[fd].write(buffer, 0, readNum);

			// error when writing less than required bytes (disk out of space)
			if (writeNum < readNum)
				return -1;

			bytesWritten += writeNum;
			size -= bufferSize;
			vaddr += bufferSize;
		}

		// Lib.debug(dbgProcess, readVirtualMemoryString(vaddr, size));
		return bytesWritten;

	}

	/*
	 * Handle the close() system call.
	 */
	private int handleClose(int fd) {
		Lib.debug(dbgProcess, "UserProcess.handleClose fd:" + String.valueOf(fd));
		if (fd > fdTable.length || fd < 0) {
			Lib.debug(dbgProcess, "fd: " + String.valueOf(fd) + " out of bound");
			return -1;
		}
		if (fdTable[fd] == null) {
			Lib.debug(dbgProcess, "fd: " + String.valueOf(fd) + " dose not exist or haven't been opened");
			return -1;
		}

		fdTable[fd].close();
		fdTable[fd] = null;
		Lib.debug(dbgProcess, "close successfully");
		return 0;
	}

	/**
	 * Handle the exit() system call.
	 */
	private int handleExit(int status) {
		// Do not remove this call to the autoGrader...
		Machine.autoGrader().finishingCurrentProcess(status);
		// ...and leave it as the top of handleExit so that we
		// can grade your implementation.

		Lib.debug(dbgProcess, "UserProcess.handleExit (" + status + ")");
		// for now, unconditionally terminate with just one process

		unloadSections(); // free all physical pages
		// close all opened files
		for (int i = 0; i < fdTable.length; i++) {
			if (fdTable[i] != null) {
				fdTable[i].close();
				fdTable[i] = null;
			}
		}
		coff.close();

		UserKernel.running_process_lock.acquire();
		UserKernel.running_process_num--;
		if (UserKernel.running_process_num == 0) {
			Lib.debug(dbgProcess, "UserProcess " + pid + " is the last process that exits. Terminate");
			Kernel.kernel.terminate();
		}
		UserKernel.running_process_lock.release();

		if (parent != null) {
			// parent.children_by_pid.remove(pid);
			parent.children_exit_status.put(pid, status);
		}

		for (Map.Entry<Integer, UserProcess> e : children_by_pid.entrySet()) {
			UserProcess child = e.getValue();
			child.parent = null;
		}
		this.thread.finish();

		return 0;
	}

	private int handleUnlink(int vaddr) {
		if (vaddr < 0 || vaddr >= (pageSize * numPages)) {
			return -1;
		}
		Lib.debug(dbgProcess, "UserProcess.handleUnlink:  " + String.valueOf(vaddr));
		String fileName = readVirtualMemoryString(vaddr, 256);
		if (fileName == null) {
			Lib.debug(dbgProcess, "Failed reading filename from " + String.valueOf(vaddr) + ".");
			return -1;
		}
		if (ThreadedKernel.fileSystem.remove(fileName)) {
			Lib.debug(dbgProcess,
					"UserProcess.handleUnlink: file " + String.valueOf(vaddr) + " has been deleted successfully.");
			return 0;
		} else {
			Lib.debug(dbgProcess, "UserProcess.handleUnlink: file " + String.valueOf(vaddr)
					+ " was not deleted, something error happened .");
			return -1;
		}
	}

	// char *file, int argc, char *argv[]
	// filename points string, argv points to array of string
	private int handleExec(int file, int argc, int argv) {
		Lib.debug(dbgProcess, "UserProcess.handleExec file: " + file + " argc: " + argc + " argv: " + argv);

		if (argc < 0)
			return -1;
		// do we need to check argv + 4*argc ? A: Already handled in load
		if (argv >= pageSize * numPages || argv < 0)
			return -1;

		String fileName = readVirtualMemoryString(file, 256); // checked out of bound of file here
		if (fileName == null)
			return -1;

		String[] argvStr = new String[argc];
		byte[] argvByte = new byte[4 * argc];
		int bytesRead = readVirtualMemory(argv, argvByte);
		if (bytesRead != 4 * argc)
			return -1; // should be able to read all argc arguments successfully

		for (int i = 0; i < argc; i++) {
			int argAddr = Lib.bytesToInt(argvByte, i * 4);
			String argvi = readVirtualMemoryString(argAddr, 256);
			if (argvi == null)
				return -1;
			argvStr[i] = argvi;
		}

		UserProcess child = newUserProcess();
		if (child.execute(fileName, argvStr)) {
			// child starts executing successfully.
			this.children_by_pid.put(child.pid, child);
			child.parent = this;
			return child.pid;
		}
		// executable of the child failed to load.
		// Decrement running num that was incremented in newUserProcess.
		UserKernel.running_process_lock.acquire();
		UserKernel.running_process_num--;
		UserKernel.running_process_lock.release();
		return -1;
	}

	private int handleJoin(int processId, int vaddr) {
		Lib.debug(dbgProcess, "UserProcess.handleJoin processId: " + processId + " vaddr: " + vaddr);
		// If processID does not refer to a child process of the current process,
		// returns -1.
		if (children_by_pid.get(processId) == null) {
			return -1;
		}

		UThread childProcess = children_by_pid.get(processId).thread;
		childProcess.join();
		// disown child
		children_by_pid.get(processId).parent = null;
		children_by_pid.remove(processId);

		// modify handleException so that we keep track of whether the exception gets
		// handled
		// A: handleExit will add its status to children_exit_status
		// A: but handleException will not
		if (children_exit_status.get(processId) == null) {
			return 0;
		}

		byte[] array = Lib.bytesFromInt(children_exit_status.get(processId));
		// if status is a null pointer (0x0), then join operates normally but does not
		// return the exit value of the child
		if (vaddr != 0x0) {
			if (writeVirtualMemory(vaddr, array) < 4) {
				return -1;
			}
		}
		return 1;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0      the first syscall argument.
	 * @param a1      the second syscall argument.
	 * @param a2      the third syscall argument.
	 * @param a3      the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
			case syscallHalt:
				return handleHalt();
			case syscallExit:
				return handleExit(a0);
			case syscallCreate:
				return handleCreate(a0);
			case syscallOpen:
				return handleOpen(a0);
			case syscallRead:
				return handleRead(a0, a1, a2);
			case syscallWrite:
				return handleWrite(a0, a1, a2);
			case syscallClose:
				return handleClose(a0);
			case syscallUnlink:
				return handleUnlink(a0);
			case syscallExec:
				return handleExec(a0, a1, a2);
			case syscallJoin:
				return handleJoin(a0, a1);

			default:
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
				Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	// TODO?
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
			case Processor.exceptionSyscall:
				int result = handleSyscall(processor.readRegister(Processor.regV0),
						processor.readRegister(Processor.regA0),
						processor.readRegister(Processor.regA1),
						processor.readRegister(Processor.regA2),
						processor.readRegister(Processor.regA3));
				processor.writeRegister(Processor.regV0, result);
				processor.advancePC();
				break;

			default:
				Lib.debug(dbgProcess, "Unexpected exception: "
						+ Processor.exceptionNames[cause]);

				unloadSections(); // free all physical pages
				// close all opened files
				for (int i = 0; i < fdTable.length; i++) {
					if (fdTable[i] != null) {
						fdTable[i].close();
						fdTable[i] = null;
					}
				}
				coff.close();

				UserKernel.running_process_lock.acquire();
				UserKernel.running_process_num--;
				if (UserKernel.running_process_num == 0) {
					Lib.debug(dbgProcess, "UserProcess " + pid + " is the last process that exits. Terminate");
					Kernel.kernel.terminate();
				}
				UserKernel.running_process_lock.release();

				// if(parent != null){
				// parent.children_by_pid.remove(pid);
				// }

				for (Map.Entry<Integer, UserProcess> e : children_by_pid.entrySet()) {
					UserProcess child = e.getValue();
					child.parent = null;
				}
				this.thread.finish();
				Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	/** The thread that executes the user-level program. */
	protected UThread thread;

	// First 2 are std in and out
	private OpenFile[] fdTable = new OpenFile[16];

	private int pid;

	private UserProcess parent;

	/** Map pid to children processes */
	private HashMap<Integer, UserProcess> children_by_pid;

	/** Map pid to exit status of child with that pid */
	private HashMap<Integer, Integer> children_exit_status;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	protected int stackStartPage;
}
