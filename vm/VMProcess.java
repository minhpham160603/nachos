package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		// ppn to vpn
		invertedPageTable = new TranslationEntry[physicalPages];
		for (int i = 0; i < physicalPages; i++) {
			invertedPageTable[i] = new TranslationEntry();
		}
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		super.restoreState();
	}

	/**
	 * Load page from disk back to physical memory
	 */
	private int getPageFromDisk(int vpn) {

		return 0;
	}

	private boolean checkPinAll(){
		for (int b: VMKernel.pinTable){
			if (b == 0){
				return false;
			}
		}
		return true;
	}

	/**
	 * Choose a physical page to evict, store it in the swap file.
	 */
	private int handleOutOfMemory() {
		while (checkPinAll()){
			VMKernel.pinCondition.sleep();
		}
		// while loop is ok because we set used to false going around the clock
		while (true) {
			int ppn = clock_hand;
			clock_hand = (clock_hand + 1) % physicalPages;
			VMProcess owner = VMKernel.ppnToProcessTable[ppn];
			if (owner == null) {
				System.out.println("owner null " + ppn);
				return ppn;
			}
			// if pinned, just skip
			if (VMKernel.pinTable[ppn] > 0){
				continue;
			}
			int vpn = owner.invertedPageTable[ppn].vpn;

			if (owner.pageTable[vpn].used) {
				owner.pageTable[vpn].used = false;
			} else {
				owner.pageTable[vpn].valid = false;
				if(!owner.pageTable[vpn].dirty && owner.pageTable[vpn].vpn != -1){
					return ppn;
				}
				int spn = owner.pageTable[vpn].vpn;
				if (spn == -1) {
					spn = VMKernel.allocateDiskPage();
					owner.pageTable[vpn].vpn = spn;
				}
				byte[] memory = Machine.processor().getMemory();
				VMKernel.pinTable[ppn] += 1;
				VMKernel.vmMutex.release();
				int res = VMKernel.swapFile.write(spn * pageSize, memory, ppn * pageSize, pageSize);
				VMKernel.vmMutex.acquire();
				VMKernel.pinTable[ppn] -= 1;
				Lib.assertTrue(VMKernel.pinTable[ppn] >= 0);
				if (VMKernel.pinTable[ppn] == 0)
					VMKernel.pinCondition.wakeAll();
				Machine.incrNumSwapWrites();
				// pageTable[vpn].used = true;
				return ppn;
			}
		}
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		UserKernel.mutex.acquire();
		// if (numPages > UserKernel.freePages.size()) {
		// coff.close();
		// Lib.debug(dbgProcess, "\tinsufficient physical memory");
		// UserKernel.mutex.release();
		// return false;
		// }

		pageTable = new TranslationEntry[numPages];
		for (int vpn = 0; vpn < numPages; vpn++) {
			boolean readOnly = false;
			if (vpn < coff.getNumSections()) {
				readOnly = coff.getSection(vpn).isReadOnly();
			}
			// int ppn = UserKernel.freePages.removeFirst();
			// TranslationEntry(int vpn, int ppn, boolean valid, boolean readOnly, boolean
			// used, boolean dirty)
			pageTable[vpn] = new TranslationEntry(-1, -1, false, readOnly, false, false);
		}
		UserKernel.mutex.release();

		return true;
	}

	// TODO: change used
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		int amount = 0;
		byte[] memory = Machine.processor().getMemory();

		if (vaddr < 0 || vaddr >= pageSize * numPages) {
			return 0;
		}

		int vpn = Processor.pageFromAddress(vaddr);
		if (vpn >= numPages) {
			return 0;
		}

		// check pagefault
		if (!pageTable[vpn].valid) {
			handlePageFault(vaddr);
		}

		pageTable[vpn].used = true;
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
			// check pagefault
			if (!pageTable[i].valid) {
				int fault_vaddr = Processor.makeAddress(i, 0);
				handlePageFault(fault_vaddr);
			}
			if (i >= numPages) {
				return amount;
			}
			ppn = pageTable[i].ppn;
			int paddr = Processor.makeAddress(ppn, 0);
			System.arraycopy(memory, paddr, data, offset + amount, pageSize);
			amount += pageSize;
		}

		// read Tail
		if (i >= numPages) {
			return amount;
		}
		int dataLeft = length - amount;
		if (dataLeft == 0) {
			return amount;
		}
		if (dataLeft > pageSize) {
			return -1;
		} // for debug, this should not happen
			// check pagefault
		// Lib.assertTrue(pageTable[i].valid, "Page invalid after valideted");
		if (!pageTable[i].valid) {
			int fault_vaddr = Processor.makeAddress(i, 0);
			handlePageFault(fault_vaddr);
		}
		ppn = pageTable[i].ppn;
		int paddr = Processor.makeAddress(ppn, 0);
		System.arraycopy(memory, paddr, data, offset + amount, dataLeft);
		amount += dataLeft;
		return amount;
	}

	// TODO: change used
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		// Do we transfer part of data or just return 0 when vaddr + length >= pageSize
		// * numPages? A: check in handleRead
		if (vaddr < 0 || vaddr >= pageSize * numPages) {
			return 0;
		}

		int vpn = Processor.pageFromAddress(vaddr);
		if (vpn >= numPages || pageTable[vpn].readOnly) {
			return 0;
		}

		// check pagefault
		if (!pageTable[vpn].valid) {
			handlePageFault(vaddr);
		}

		int offsetInPage = Processor.offsetFromAddress(vaddr);

		pageTable[vpn].dirty = true;
		pageTable[vpn].used = true;
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
			if (i >= numPages || pageTable[i].readOnly) {
				return amount;
			}
			
			// check pagefault
			if (!pageTable[i].valid) {
				int fault_vaddr = Processor.makeAddress(i, 0);
				handlePageFault(fault_vaddr);
			}

			pageTable[i].dirty = true;
			ppn = pageTable[i].ppn;
			int paddr = Processor.makeAddress(ppn, 0);
			System.arraycopy(data, offset + amount, memory, paddr, pageSize);
			amount += pageSize;
		}

		// write Tail
		if (i >= numPages || pageTable[i].readOnly) {
			return amount;
		}
		
		int dataLeft = length - amount;
		if (dataLeft == 0) {
			return amount;
		}
		if (dataLeft > pageSize) {
			return -1;
		} 

		if (!pageTable[i].valid) {
			int fault_vaddr = Processor.makeAddress(i, 0);
			handlePageFault(fault_vaddr);
		}
		pageTable[i].dirty = true;
		ppn = pageTable[i].ppn;
		int paddr = Processor.makeAddress(ppn, 0);
		System.arraycopy(data, offset + amount, memory, paddr, dataLeft);
		amount += dataLeft;
		return amount;
	}

	private void handlePageFault(int vBadAddress) {
		if (vBadAddress < stackStartPage * pageSize) { // coff section
			loadCoffPage(vBadAddress);
		} else { // fill with zero
			loadStackPage(vBadAddress);
		}
	}

	protected void loadFromDisk(int spn, int ppn) {
		byte[] memory = Machine.processor().getMemory();
		// use read() interface of StubOpenFile to read from swap file to memory at
		VMKernel.pinTable[ppn] += 1;
		VMKernel.vmMutex.release();
		VMKernel.swapFile.read(spn * pageSize, memory, ppn * pageSize, pageSize);
		VMKernel.vmMutex.acquire();
		VMKernel.pinTable[ppn] -= 1;
		Lib.assertTrue(VMKernel.pinTable[ppn] >= 0);
		if (VMKernel.pinTable[ppn] == 0)
			VMKernel.pinCondition.wakeAll();
		Machine.incrNumSwapReads();
	}

	// load the coff page
	protected int loadCoffPage(int vBadAddress) {
		VMKernel.vmMutex.acquire();
		int vpn = Processor.pageFromAddress(vBadAddress);

		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			if (section.getFirstVPN() <= vpn && section.getFirstVPN() + section.getLength() > vpn) {
				// System.out.println("free pages " + VMKernel.freePages.size());
				int ppn = UserKernel.allocatePhysPage();
				int spn = pageTable[vpn].vpn;
				String msg = "coff ppn " + ppn + " for vpn " + vpn;
				if (ppn < 0) {
					ppn = handleOutOfMemory();
					msg += " OOM: new ppn " + ppn;
				}
				// Lib.debug(dbgProcess, msg);
				pageTable[vpn].ppn = ppn;
				pageTable[vpn].valid = true;
				pageTable[vpn].dirty = false;
				invertedPageTable[ppn].vpn = vpn;
				VMKernel.ppnToProcessTable[ppn] = this;

				if (spn == -1) {
					VMKernel.pinTable[ppn] += 1;
					VMKernel.vmMutex.release();
					section.loadPage(vpn - section.getFirstVPN(), ppn);
					VMKernel.vmMutex.acquire();
					VMKernel.pinTable[ppn] -= 1;
					Lib.assertTrue(VMKernel.pinTable[ppn] >= 0);
					if (VMKernel.pinTable[ppn] == 0)
						VMKernel.pinCondition.wakeAll();
				} else {
					loadFromDisk(spn, ppn);
				}
				VMKernel.vmMutex.release();
				return 0;
			}
		}
		VMKernel.vmMutex.release();
		return -1;
	}

	protected int loadStackPage(int vBadAddress) {
		VMKernel.vmMutex.acquire();
		int vpn = Processor.pageFromAddress(vBadAddress);
		int ppn = UserKernel.allocatePhysPage();
		int spn = pageTable[vpn].vpn;

		String msg = "stack ppn " + ppn + " for vpn " + vpn;
		if (ppn < 0) {
			ppn = handleOutOfMemory();
			msg += " OOM: new ppn " + ppn;
		}
		// Lib.debug(dbgProcess, msg);

		if (ppn < 0) {
			ppn = handleOutOfMemory();
		}
		pageTable[vpn].ppn = ppn;
		pageTable[vpn].valid = true;
		pageTable[vpn].dirty = false;
		invertedPageTable[ppn].vpn = vpn;
		VMKernel.ppnToProcessTable[ppn] = this;
		if (spn == -1) {
			byte[] memory = Machine.processor().getMemory();
			for (int i = 0; i < pageSize; i++) {
				memory[ppn * pageSize + i] = (byte) 0;
			}
		} else {
			loadFromDisk(spn, ppn);
		}
		VMKernel.vmMutex.release();
		return 0;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	// called by handleExit
	protected void unloadSections() {
		// super.unloadSections();
		// release the swap file pages
		VMKernel.vmMutex.acquire();
		for (int i = 0; i < pageTable.length; i++) {
			if (pageTable[i].vpn != -1) {
				VMKernel.freeDiskPage(pageTable[i].vpn);
			}
		}
		// TODO: need to change the ppnToProcessTable
		for (int i = 0; i < VMKernel.ppnToProcessTable.length; i++) {
			if (VMKernel.ppnToProcessTable[i] == this) {
				VMKernel.ppnToProcessTable[i] = null;
			}
		}
		for (int vpn = 0; vpn < numPages; vpn++) {
			int ppn = pageTable[vpn].ppn;
			if ((ppn != -1) && pageTable[vpn].valid) {
				VMKernel.freePhysPage(ppn);
			}
		}
		System.out.println("Free all pages " + VMKernel.freePages.size());
		VMKernel.vmMutex.release();
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();
		switch (cause) {
			case Processor.exceptionPageFault:
				handlePageFault(processor.readRegister(Processor.regBadVAddr));
				break;
			default:
				super.handleException(cause);
				break;
		}
	}

	private static final int pageSize = Processor.pageSize;

	private static final int physicalPages = Machine.processor().getNumPhysPages();

	private int clock_hand = 0;

	// invertedPageTable cannot be static because we need one for each process
	private TranslationEntry[] invertedPageTable;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
