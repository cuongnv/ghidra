/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.opinion;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import ghidra.app.cmd.register.SetRegisterCmd;
import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MemoryConflictHandler;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.store.LockException;
import ghidra.javaclass.format.*;
import ghidra.javaclass.format.attributes.CodeAttribute;
import ghidra.javaclass.format.constantpool.AbstractConstantPoolInfoJava;
import ghidra.javaclass.format.constantpool.ConstantPoolUtf8Info;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.task.TaskMonitor;

public class JavaLoader extends AbstractLibrarySupportLoader {

	private static final String JAVA_NAME = "Java Class File";
	private Register alignmentReg;

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		List<LoadSpec> loadSpecs = new ArrayList<>();
		boolean validClass = false;

		if (checkClass(provider)) {
			validClass = true;
		}

		if (validClass) {
			loadSpecs.add(new LoadSpec(this, 0,
				new LanguageCompilerSpecPair("JVM:BE:32:default", "default"), true));
		}

		return loadSpecs;
	}

	private boolean checkClass(ByteProvider provider) {
		BinaryReader reader = new BinaryReader(provider, false);
		ClassFileJava classFile;
		try {
			classFile = new ClassFileJava(reader);
		}
		catch (IOException e) {
			return false;
		} catch (RuntimeException re) {
			return false;
		}
		int magic = classFile.getMagic();
		if (magic == 0xCAFEBABE) {
			return true;
		}
		return false;
	}

	@Override
	public String getName() {
		return JAVA_NAME;
	}

	@Override
	public void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			Program program, MemoryConflictHandler handler, TaskMonitor monitor, MessageLog log)
			throws IOException {
		try {
			doLoad(provider, program, monitor);
		}
		catch (LockException e) {
			e.printStackTrace();
		}
		catch (MemoryConflictException e) {
			e.printStackTrace();
		}
		catch (AddressOverflowException e) {
			e.printStackTrace();
		}
		catch (CancelledException e) {
			e.printStackTrace();
		}
		catch (DuplicateNameException e) {
			e.printStackTrace();
		}
	}

	public void load(ByteProvider provider, Program program, TaskMonitor monitor)
			throws IOException {
		load(provider, null, null, program, null, monitor, null);
	}

	private void doLoad(ByteProvider provider, Program program, TaskMonitor monitor)
			throws LockException, MemoryConflictException, AddressOverflowException,
			CancelledException, DuplicateNameException, IOException {
		AddressFactory af = program.getAddressFactory();
		AddressSpace space = af.getAddressSpace("constantPool");
		Memory memory = program.getMemory();
		alignmentReg = program.getRegister("alignmentPad");

		BinaryReader reader = new BinaryReader(provider, false);
		ClassFileJava classFile = new ClassFileJava(reader);

		Address address = space.getAddress(0);

		// Create a block of memory with just the right size
		memory.createInitializedBlock("_" + provider.getName() + "_", address,
			provider.getInputStream(0), provider.length(), monitor, false);

		createMethodLookupMemoryBlock( program, monitor );
		createMethodMemoryBlocks(program, provider, classFile, monitor);

	}



	private void createMethodLookupMemoryBlock(Program program, TaskMonitor monitor) {
		Address address = toAddr( program, JavaClassUtil.LOOKUP_ADDRESS );
		MemoryBlock block = null;
		Memory memory = program.getMemory();
		try {
			block = memory.createInitializedBlock( "method_lookup", address, JavaClassUtil.METHOD_INDEX_SIZE, (byte) 0xff, monitor, false );
		}
		catch (LockException | DuplicateNameException | MemoryConflictException
				| AddressOverflowException | CancelledException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		block.setRead( true );
		block.setWrite( false );
		block.setExecute( false );

	}

	private void createMethodMemoryBlocks(Program program, ByteProvider provider,
			ClassFileJava classFile, TaskMonitor monitor) {
		AbstractConstantPoolInfoJava[] constantPool = classFile.getConstantPool();
		MethodInfoJava[] methods = classFile.getMethods();

		monitor.setMessage("Processing Methods...");
		monitor.setProgress(0);
		monitor.setMaximum(methods.length);

		long codeOffset = 0x10000;
		Address start = toAddr(program, codeOffset);
		try {
			//program.setImageBase(start, true);
			//for (MethodInfoJava method : methods) {
			for (int i = 0, max = methods.length; i < max; ++i){
				MethodInfoJava method = methods[i];
				monitor.incrementProgress(1);
				CodeAttribute code = method.getCodeAttribute();
				if (code == null) {
					continue;
				}
				int length = code.getCodeLength();
				long offset = code.getCodeOffset();

				Memory memory = program.getMemory();
				short nameIndex = method.getNameIndex();
				short descriptorIndex = method.getDescriptorIndex();
				ConstantPoolUtf8Info methodNameInfo = (ConstantPoolUtf8Info) constantPool[nameIndex];
				ConstantPoolUtf8Info methodDescriptorInfo =
						(ConstantPoolUtf8Info) constantPool[descriptorIndex];
				String methodName = methodNameInfo.getString() + methodDescriptorInfo.getString();

				MemoryBlock memoryBlock =
						memory.createInitializedBlock(methodName, start,
							provider.getInputStream(offset), length, monitor, false);
				Address methodIndexAddress = JavaClassUtil.toLookupAddress( program, i );
				program.getMemory( ).setInt( methodIndexAddress, (int) start.getOffset() );

				setAlignmentInfo(program,
					new AddressSet(memoryBlock.getStart(), memoryBlock.getEnd()));
				start = start.add(length + 1);
				while (start.getOffset() % 4 != 0) {
					start = start.add(1);
				}



			}
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	private void setAlignmentInfo(Program program, AddressSet set) {
		AddressIterator addressIterator = set.getAddresses(true);
		int alignmentValue = 3;
		while (addressIterator.hasNext()) {
			Address address = addressIterator.next();
			SetRegisterCmd cmd =
					new SetRegisterCmd(alignmentReg, address, address,
						BigInteger.valueOf(alignmentValue));
			cmd.applyTo(program);
			if (alignmentValue == 0) {
				alignmentValue = 3;
			}
			else {
				alignmentValue--;
			}
		}
	}

	private Address toAddr(Program program, long offset) {
		return program.getAddressFactory().getDefaultAddressSpace().getAddress(offset);
	}

}
