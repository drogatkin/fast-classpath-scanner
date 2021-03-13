/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.lukehutch.fastclasspathscanner;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by
 * parsing the classfile binary format directly rather than by using reflection.
 * (Reflection causes the classloader to load each class, which can take an
 * order of magnitude more time than parsing the classfile directly.)
 * 
 * This classpath scanner is able to scan directories and jar/zip files on the
 * classpath to:
 * 
 * (1) find classes that subclass a given class or one of its subclasses;
 * 
 * (2) find classes that implement an interface or one of its subinterfaces;
 * 
 * (3) find interfaces that extend another given interface;
 * 
 * (4) find classes that have a given annotation;
 * 
 * (5) find classes that contain a specific static final field, returning the
 * constant literal value used to initialize the field in the classfile;
 * 
 * (6) find file paths (even for non-classfiles) anywhere on the classpath that
 * match a given regexp;
 * 
 * (7) detect changes to the contents of the classpath after the initial scan;
 * and
 * 
 * (8) return a list of all directories and files on the classpath as a list of
 * File objects, with the list deduplicated and filtered to include only
 * classpath directories and files that actually exist.
 * 
 * Usage example: (Note that these examples use Java 8 lambda expressions, which
 * at least as of JDK 1.8.0 r20 incur a one-time startup cost of 30-40ms):
 * 
 * <code>
 *     new FastClasspathScanner(
 *           // Whitelisted package prefixes to scan:
 *           new String[] { "com.xyz.widget", "com.xyz.gizmo" })
 * 
 *       .matchSubclassesOf(DBModel.class,
 *           // c is a subclass of DBModel or a descendant subclass
 *           c -> System.out.println("Subclass of DBModel: " + c.getName()))
 * 
 *       .matchSubinterfacesOf(Role.class,
 *           // c is an interface that extends the interface Role
 *           c -> System.out.println("Subinterface of Role: " + c.getName()))
 * 
 *       .matchClassesImplementing(Runnable.class,
 *           // c is a class that implements the interface Runnable; more precisely,
 *           // c or one of its superclasses implements the interface Runnable, or
 *           // implements an interface that is a descendant of Runnable
 *           c -> System.out.println("Implements Runnable: " + c.getName()))
 * 
 *       .matchClassesWithAnnotation(RestHandler.class,
 *           // c is a class annotated with RestHandler
 *           c -> System.out.println("Has a RestHandler class annotation: " + c.getName()))
 * 
 *       .matchStaticFinalFieldNames(
 *           Stream.of("com.xyz.Config.POLL_INTERVAL", "com.xyz.Config.LOG_LEVEL")
 *                   .collect(Collectors.toCollection(HashSet::new)),
 *               // The following method is called when any static final fields with
 *               // names matching one of the above fully-qualified names are
 *               // encountered, as long as those fields are initialized to constant
 *               // values. The value returned is the value in the classfile, not the
 *               // value that would be returned by reflection, so this can be useful
 *               // in hot-swapping of changes to static constants in classfiles if
 *               // the constant value is changed and the class is re-compiled while
 *               // the code is running. (Eclipse doesn't hot-replace static constant
 *               // initializer values if you change them while running code in the
 *               // debugger, so you can pick up changes this way instead). 
 *               // Note that the visibility of the fields is not checked; the value
 *               // of the field in the classfile is returned whether or not it
 *               // should be visible. 
 *               (String className, String fieldName, Object fieldConstantValue) ->
 *                   System.out.println("Static field " + fieldName + " of class "
 *                       + className + " " + " has constant literal value "
 *                       + fieldConstantValue + " in classfile"))
 * 
 *       .matchFilenamePattern("^template/.*\\.html",
 *           // templatePath is a path on the classpath that matches the above
 *           // pattern; inputStream is a stream opened on the file or zipfile entry
 *           // No need to close inputStream before exiting, it is closed by caller.
 *           (absolutePath, relativePath, inputStream) -> {
 *              try {
 *                  String template = IOUtils.toString(inputStream, "UTF-8");
 *                  System.out.println("Found template: " + absolutePath
 *                          + " (size " + template.length() + ")");
 *              } catch (IOException e) {
 *                  throw new RuntimeException(e);
 *              }
 *          })
 * 
 *       .scan();  // Actually perform the scan
 *       
 *       
 *    // [...Some time later...]
 *    
 *    // See if any timestamps on the classpath are more recent than the time of the
 *    // previous scan. (Even faster than classpath scanning, because classfiles
 *    // don't have to be opened.)   
 *    boolean classpathContentsModified =
 *        fastClassPathScanner.classpathContentsModifiedSinceScan();
 * 
 * </code>
 * 
 * Note that you need to pass a whitelist of package prefixes to scan into the
 * constructor, and the ability to detect that a class or interface extends
 * another depends upon the entire ancestral path between the two classes or
 * interfaces having one of the whitelisted package prefixes.
 * 
 * When matching involves classfiles (i.e. in all cases except
 * FastClasspathScanner#matchFilenamePattern, which deals with arbitrary files
 * on the classpath), if the same fully-qualified class name is encountered more
 * than once on the classpath, the second and subsequent definitions of the
 * class are ignored.
 * 
 * The scanner also records the latest last-modified timestamp of any file or
 * directory encountered, and you can see if that latest last-modified timestamp
 * has increased (indicating that something on the classpath has been updated)
 * by calling classpathContentsModifiedSinceScan(). This can be used to enable
 * dynamic class-reloading if something on the classpath is updated, for example
 * to support hot-replace of route handler classes in a webserver.
 * classpathContentsModifiedSinceScan() is several times faster than the
 * original call to scan(), since only modification timestamps need to be
 * checked.
 * 
 * Inspired by:
 * https://github.com/rmuller/infomas-asl/tree/master/annotation-detector
 * 
 * See also:
 * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4
 * 
 * Please let me know if you find this useful!
 */
public class FastClasspathScanner {

	/**
	 * List of directory path prefixes to scan (produced from list of package
	 * prefixes passed into the constructor)
	 */
	private final String[] pathsToScan;

	/**
	 * The latest last-modified timestamp of any file, directory or
	 * sub-directory in the classpath, in millis since the Unix epoch. Does not
	 * consider timestamps inside zipfiles/jarfiles, but the timestamp of the
	 * zip/jarfile itself is considered.
	 */
	private long lastModified = 0;

	/**
	 * If this is set to true, then the timestamps of zipfile entries should be
	 * used to determine when files inside a zipfile have changed; if set to
	 * false, then the timestamp of the zipfile itself is used. Itis recommended
	 * to leave this set to false, since zipfile timestamps are less trustworthy
	 * than filesystem timestamps.
	 */
	private static final boolean USE_ZIPFILE_ENTRY_MODIFICATION_TIMES = false;

	/**
	 * A list of class matchers to call once all classes have been read in from
	 * classpath.
	 */
	private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

	/**
	 * A list of file path matchers to call when a directory or subdirectory on
	 * the classpath matches a given regexp.
	 */
	private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

	/**
	 * A map from fully-qualified class name, to static field name, to a
	 * StaticFieldMatchProcessor to call when the class name and static field
	 * name matches for a static field in a classfile.
	 */
	private final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> //
	classNameToStaticFieldnameToMatchProcessor = new HashMap<>();

	/**
	 * Classes encountered so far during a scan. If the same fully-qualified
	 * classname is encountered more than once, the second and subsequent
	 * instances are ignored, because they are masked by the earlier occurrence
	 * in the classpath.
	 */
	private final HashSet<String> classesEncounteredSoFarDuringScan = new HashSet<>();

	/** The class and interface graph builder. */
	private final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder();

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Construct a FastClasspathScanner instance.
	 * 
	 * @param packagesToScan
	 *            package prefixes to scan, e.g. new String[] {
	 *            "com.xyz.widget", "com.xyz.gizmo" }
	 */
	public FastClasspathScanner(String... packagesToScan) {
		HashSet<String> uniquePathsToScan = new HashSet<>();
		for (String packageToScan : packagesToScan) {
			uniquePathsToScan.add(packageToScan.replace('.', '/') + "/");
		}
		this.pathsToScan = new String[uniquePathsToScan.size()];
		int i = 0;
		for (String uniquePathToScan : uniquePathsToScan) {
			this.pathsToScan[i++] = uniquePathToScan;
		}
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the provided SubclassMatchProcessor if classes are found on the
	 * classpath that extend the specified superclass.
	 * 
	 * @param superclass
	 *            The superclass to match (i.e. the class that subclasses need
	 *            to extend to match).
	 * @param subclassMatchProcessor
	 *            the SubclassMatchProcessor to call when a match is found.
	 */
	@SuppressWarnings("unchecked")
	public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
			final SubclassMatchProcessor<T> subclassMatchProcessor) {
		if (superclass.isInterface()) {
			throw new IllegalArgumentException(superclass.getName() + " is an interface, not a regular class");
		}
		classMatchers.add(new ClassMatcher() {
			@Override
			public void lookForMatches() {
				for (String subclass : classGraphBuilder.getSubclassesOf(superclass.getName())) {
					try {
						// Load class
						Class<? extends T> klass = (Class<? extends T>) Class.forName(subclass);

						// Process match
						subclassMatchProcessor.processMatch(klass);

					} catch (ClassNotFoundException | NoClassDefFoundError e) {
						throw new RuntimeException(e);
					}
				}
			}
		});
		return this;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the provided SubInterfaceMatchProcessor if an interface that extends
	 * a given superinterface is found on the classpath.
	 * 
	 * @param superInterface
	 *            The superinterface to match (i.e. the interface that
	 *            subinterfaces need to extend to match).
	 * @param subinterfaceMatchProcessor
	 *            the SubinterfaceMatchProcessor to call when a match is found.
	 */
	@SuppressWarnings("unchecked")
	public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superInterface,
			final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
		if (!superInterface.isInterface()) {
			throw new IllegalArgumentException(superInterface.getName() + " is not an interface");
		}
		classMatchers.add(new ClassMatcher() {
			@Override
			public void lookForMatches() {
				for (String subInterface : classGraphBuilder.getSubinterfacesOf(superInterface.getName())) {
					try {
						// Load interface
						Class<? extends T> klass = (Class<? extends T>) Class.forName(subInterface);

						// Process match
						subinterfaceMatchProcessor.processMatch(klass);

					} catch (ClassNotFoundException | NoClassDefFoundError e) {
						throw new RuntimeException(e);
					}
				}
			}
		});
		return this;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the provided InterfaceMatchProcessor for classes on the classpath
	 * that implement the specified interface or a subinterface, or whose
	 * superclasses implement the specified interface or a sub- interface.
	 * 
	 * @param implementedInterface
	 *            The interface that classes need to implement to match.
	 * @param interfaceMatchProcessor
	 *            the ClassMatchProcessor to call when a match is found.
	 */
	@SuppressWarnings("unchecked")
	public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
			final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
		if (!implementedInterface.isInterface()) {
			throw new IllegalArgumentException(implementedInterface.getName() + " is not an interface");
		}
		classMatchers.add(new ClassMatcher() {
			@Override
			public void lookForMatches() {
				// For all classes implementing the given interface
				ArrayList<String> interfaceImpls = classGraphBuilder.getClassesImplementing(implementedInterface
						.getName());
				if (interfaceImpls != null)
					for (String implClass : interfaceImpls) {
						try {
							// Load class
							Class<? extends T> klass = (Class<? extends T>) Class.forName(implClass);

							// Process match
							interfaceMatchProcessor.processMatch(klass);

						} catch (ClassNotFoundException | NoClassDefFoundError e) {
							throw new RuntimeException(e);
						}
					}
			}
		});
		return this;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the provided ClassMatchProcessor if classes are found on the
	 * classpath that have the specified annotation.
	 * 
	 * @param annotation
	 *            The class annotation to match.
	 * @param classAnnotationMatchProcessor
	 *            the ClassAnnotationMatchProcessor to call when a match is
	 *            found.
	 */
	public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
			final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
		if (!annotation.isAnnotation()) {
			throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
		}
		classMatchers.add(new ClassMatcher() {
			@Override
			public void lookForMatches() {
				// For all classes with the given annotation
				ArrayList<String> classesWithAnnotation = classGraphBuilder.getClassesWithAnnotation(annotation
						.getName());
				if (classesWithAnnotation != null)
					for (String classWithAnnotation : classesWithAnnotation) {
						try {
							// Load class
							ClassLoader cl = getClassLoader();
							Class<?> klass = cl == null ? Class.forName(classWithAnnotation) : Class.forName(
									classWithAnnotation, false, cl);

							// Process match
							classAnnotationMatchProcessor.processMatch(klass);

						} catch (ClassNotFoundException | NoClassDefFoundError e) {
							throw new RuntimeException(e);
						}
					}
			}
		});
		return this;
	}

	public ClassLoader getClassLoader() {
		return null;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the given StaticFinalFieldMatchProcessor if classes are found on the
	 * classpath that contain static final fields that match one of a set of
	 * fully-qualified field names, e.g.
	 * "com.package.ClassName.STATIC_FIELD_NAME".
	 * 
	 * Field values are obtained from the constant pool in classfiles, *not*
	 * from a loaded class using reflection. This allows you to detect changes
	 * to the classpath and then run another scan that picks up the new values
	 * of selected static constants without reloading the class. (Class
	 * reloading is fraught with issues, see:
	 * http://tutorials.jenkov.com/java-reflection
	 * /dynamic-class-loading-reloading.html )
	 * 
	 * Note: Only static final fields with constant-valued literals are matched,
	 * not fields with initializer values that are the result of an expression
	 * or reference, except for cases where the compiler is able to simplify an
	 * expression into a single constant at compiletime, such as in the case of
	 * string concatenation.
	 * 
	 * @param fullyQualifiedStaticFinalFieldNames
	 *            The set of fully-qualified static field names to match.
	 * @param staticFinalFieldMatchProcessor
	 *            the StaticFinalFieldMatchProcessor to call when a match is
	 *            found.
	 */
	public FastClasspathScanner matchStaticFinalFieldNames(final HashSet<String> fullyQualifiedStaticFinalFieldNames,
			final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
		for (String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
			int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
			if (lastDotIdx > 0) {
				String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
				String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
				HashMap<String, StaticFinalFieldMatchProcessor> fieldNameToMatchProcessor = classNameToStaticFieldnameToMatchProcessor
						.get(className);
				if (fieldNameToMatchProcessor == null) {
					classNameToStaticFieldnameToMatchProcessor.put(className,
							fieldNameToMatchProcessor = new HashMap<>());
				}
				fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
			}
		}
		return this;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Call the given FileMatchProcessor if files are found on the classpath
	 * with the given regexp pattern in their path.
	 * 
	 * @param filenameMatchPattern
	 *            The regexp to match, e.g. "app/templates/.*\\.html"
	 * @param fileMatchProcessor
	 *            The FileMatchProcessor to call when each match is found.
	 */
	public FastClasspathScanner matchFilenamePattern(final String filenameMatchPattern,
			final FileMatchProcessor fileMatchProcessor) {
		filePathMatchers.add(new FilePathMatcher(Pattern.compile(filenameMatchPattern), fileMatchProcessor));
		return this;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * An interface used for testing if a file path matches a specified pattern.
	 */
	private static class FilePathMatcher {
		Pattern pattern;
		FileMatchProcessor fileMatchProcessor;

		public FilePathMatcher(Pattern pattern, FileMatchProcessor fileMatchProcessor) {
			this.pattern = pattern;
			this.fileMatchProcessor = fileMatchProcessor;
		}
	}

	/** An interface used for testing if a class matches specified criteria. */
	private static interface ClassMatcher {
		public abstract void lookForMatches();
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Read annotation entry from classfile.
	 */
	private String readAnnotation(final DataInputStream inp, Object[] constantPool) throws IOException {
		String annotationFieldDescriptor = readRefdString(inp, constantPool);
		String annotationClassName;
		if (annotationFieldDescriptor.charAt(0) == 'L'
				&& annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
			// Lcom/xyz/Annotation; -> com.xyz.Annotation
			annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1)
					.replace('/', '.');
		} else {
			// Should not happen
			annotationClassName = annotationFieldDescriptor;
		}
		int numElementValuePairs = inp.readUnsignedShort();
		for (int i = 0; i < numElementValuePairs; i++) {
			inp.skipBytes(2); // element_name_index
			readAnnotationElementValue(inp, constantPool);
		}
		return annotationClassName;
	}

	/**
	 * Read annotation element value from classfile.
	 */
	private void readAnnotationElementValue(final DataInputStream inp, Object[] constantPool) throws IOException {
		int tag = inp.readUnsignedByte();
		switch (tag) {
		case 'B':
		case 'C':
		case 'D':
		case 'F':
		case 'I':
		case 'J':
		case 'S':
		case 'Z':
		case 's':
			// const_value_index
			inp.skipBytes(2);
			break;
		case 'e':
			// enum_const_value
			inp.skipBytes(4);
			break;
		case 'c':
			// class_info_index
			inp.skipBytes(2);
			break;
		case '@':
			// Complex (nested) annotation
			readAnnotation(inp, constantPool);
			break;
		case '[':
			// array_value
			final int count = inp.readUnsignedShort();
			for (int l = 0; l < count; ++l) {
				// Nested annotation element value
				readAnnotationElementValue(inp, constantPool);
			}
			break;
		default:
			// System.err.println("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
			break;
		}
	}

	/**
	 * Read as usigned short constant pool reference, then look up the string in
	 * the constant pool.
	 */
	private static String readRefdString(DataInputStream inp, Object[] constantPool) throws IOException {
		return (String) constantPool[inp.readUnsignedShort()];
	}

	/**
	 * Directly examine contents of classfile binary header.
	 */
	private void readClassInfoFromClassfileHeader(final InputStream inputStream) throws IOException {
		DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 1024));

		// Magic
		if (inp.readInt() != 0xCAFEBABE) {
			// Not classfile
			return;
		}

		// Minor version
		inp.readUnsignedShort();
		// Major version
		inp.readUnsignedShort();

		// Constant pool count (1-indexed, zeroth entry not used)
		int cpCount = inp.readUnsignedShort();
		// Constant pool
		Object[] constantPool = new Object[cpCount];
		//int[] tag = new int[cpCount];
		int[] indirectStringRef = new int[cpCount];
		Arrays.fill(indirectStringRef, -1);
		for (int i = 1; i < cpCount; ++i) {
			//tag[i] = inp.readUnsignedByte();
			switch (inp.readUnsignedByte()) {
			case 1: // Modified UTF8
				constantPool[i] = inp.readUTF();
				break;
			case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
				constantPool[i] = inp.readInt();
				break;
			case 4: // float
				constantPool[i] = inp.readFloat();
				break;
			case 5: // long
				constantPool[i] = inp.readLong();
				i++; // double slot
				break;
			case 6: // double
				constantPool[i] = inp.readDouble();
				i++; // double slot
				break;
			case 7: // Class
			case 8: // String
				// Forward or backward indirect reference to a modified UTF8 entry
				indirectStringRef[i] = inp.readUnsignedShort();
				break;
			case 9: // field ref
			case 10: // method ref
			case 11: // interface ref
			case 12: // name and type
				inp.skipBytes(4); // two shorts
				break;
			case 15: // method handle
				inp.skipBytes(3);
				break;
			case 16: // method type
				inp.skipBytes(2);
				break;
			case 18: // invoke dynamic
				inp.skipBytes(4);
				break;
		    case 19: // CONSTANT_Module  https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html
			case 20: // CONSTANT_Package 53.0
			// The CONSTANT_Utf8_info Structure
		        	inp.skipBytes(2);
		        	//inp.skipBytes(1);
				//int lenh = inp.readUnsignedShort();
				//inp.skipBytes(lenh);
				break;
			default:
				throw new RuntimeException("ClassfileFormatException: Unknown constant pool tag " //+ tag[i]
                        + " (element size unknown, cannot continue reading class). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
			}
		}
		// Resolve indirection of string references now that all the strings have been read
		// (allows forward references to strings before they have been encountered)
		for (int i = 1; i < cpCount; i++) {
			if (indirectStringRef[i] >= 0) {
				constantPool[i] = constantPool[indirectStringRef[i]];
			}
		}

		// Access flags
		int flags = inp.readUnsignedShort();
		boolean isInterface = (flags & 0x0200) != 0;

		// The fully-qualified class name of this class, with slashes replaced with dots
		String className = readRefdString(inp, constantPool);
		
		if (className != null) {
             className = className.replace('/', '.');
			// Determine if this fully-qualified class name has already been encountered during this scan
			if (!classesEncounteredSoFarDuringScan.add(className)) {
				// If so, skip this classfile, because the earlier class with the same name as this one
				// occurred earlier on the classpath, so it masks this one.
				return;
			}
		} 

		// Superclass name, with slashes replaced with dots
		String superclassName = readRefdString(inp, constantPool);
		if (superclassName != null) {
			superclassName = superclassName.replace('/', '.');
		}

		// Look up static field name match processors given class name 
		HashMap<String, StaticFinalFieldMatchProcessor> staticFieldnameToMatchProcessor = classNameToStaticFieldnameToMatchProcessor
				.get(className);

		// Interfaces
		int interfaceCount = inp.readUnsignedShort();
		ArrayList<String> interfaces = interfaceCount > 0 ? new ArrayList<String>() : null;
		for (int i = 0; i < interfaceCount; i++) {
			interfaces.add(readRefdString(inp, constantPool).replace('/', '.'));
		}

		// Fields
		int fieldCount = inp.readUnsignedShort();
		for (int i = 0; i < fieldCount; i++) {
			int accessFlags = inp.readUnsignedShort();
			// See http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
			boolean isStaticFinal = (accessFlags & 0x0018) == 0x0018;
			String fieldName = readRefdString(inp, constantPool);
			StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor = staticFieldnameToMatchProcessor != null ? staticFieldnameToMatchProcessor
					.get(fieldName) : null;
			String descriptor = readRefdString(inp, constantPool);
			int attributesCount = inp.readUnsignedShort();
			if (!isStaticFinal && staticFinalFieldMatchProcessor != null) {
				// Requested to match a field that is not static or not final
				System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
						+ ": cannot match requested field " + className + "." + fieldName
						+ " because it is either not static or not final");
			} else if (!isStaticFinal || staticFinalFieldMatchProcessor == null) {
				// Not matching this static final field, just skip field attributes rather than parsing them
				for (int j = 0; j < attributesCount; j++) {
					inp.skipBytes(2); // attribute_name_index
					int attributeLength = inp.readInt();
					inp.skipBytes(attributeLength);
				}
			} else {
				// Look for static final fields that match one of the requested names,
				// and that are initialized with a constant value
				boolean foundConstantValue = false;
				for (int j = 0; j < attributesCount; j++) {
					String attributeName = readRefdString(inp, constantPool);
					int attributeLength = inp.readInt();
					if (attributeName.equals("ConstantValue")) {
						// http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
						Object constValue = constantPool[inp.readUnsignedShort()];
						// byte, char, short and boolean constants are all stored as 4-byte int
						// values -- coerce and wrap in the proper wrapper class with autoboxing
						switch (descriptor) {
						case "B":
							// Convert byte store in Integer to Byte
							constValue = ((Integer) constValue).byteValue();
							break;
						case "C":
							// Convert char stored in Integer to Character
							constValue = (char) ((Integer) constValue).intValue();
							break;
						case "S":
							// Convert char stored in Integer to Short
							constValue = ((Integer) constValue).shortValue();
							break;
						case "Z":
							// Convert char stored in Integer to Boolean
							constValue = ((Integer) constValue).intValue() != 0;
							break;
						case "I":
						case "J":
						case "F":
						case "D":
						case "Ljava.lang.String;":
							// Field is int, long, float, double or String => object is already in correct
							// wrapper type (Integer, Long, Float, Double or String), nothing to do
							break;
						default:
							// Should never happen:
							// constant values can only be stored as an int, long, float, double or String
							break;
						}
						// Call static final field match processor
						staticFinalFieldMatchProcessor.processMatch(className, fieldName, constValue);
						foundConstantValue = true;
					} else {
						inp.skipBytes(attributeLength);
					}
					if (!foundConstantValue) {
						System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
								+ ": Requested static final field " + className + "." + fieldName
								+ "is not initialized with a constant literal value, so there is no "
								+ "initializer value in the constant pool of the classfile");
					}
				}
			}
		}

		// Methods
		int methodCount = inp.readUnsignedShort();
		for (int i = 0; i < methodCount; i++) {
			inp.skipBytes(6); // access_flags, name_index, descriptor_index
			int attributesCount = inp.readUnsignedShort();
			for (int j = 0; j < attributesCount; j++) {
				inp.skipBytes(2); // attribute_name_index
				int attributeLength = inp.readInt();
				inp.skipBytes(attributeLength);
			}
		}

		// Attributes (including class annotations)
		HashSet<String> annotations = null;
		int attributesCount = inp.readUnsignedShort();
		for (int i = 0; i < attributesCount; i++) {
			String attributeName = readRefdString(inp, constantPool);
			int attributeLength = inp.readInt();
			if ("RuntimeVisibleAnnotations".equals(attributeName)) {
				int annotationCount = inp.readUnsignedShort();
				for (int m = 0; m < annotationCount; m++) {
					String annotationName = readAnnotation(inp, constantPool);
					if (annotations == null) {
						annotations = new HashSet<>();
					}
					annotations.add(annotationName);
				}
			} else {
				inp.skipBytes(attributeLength);
			}
		}

		if (isInterface) {
			classGraphBuilder
					.linkToSuperinterfaces(/* interfaceName = */className, /* superInterfaces = */interfaces);

		} else {
			classGraphBuilder.linkToSuperclassAndInterfaces(className, superclassName, interfaces, annotations);
		}
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Scan a file.
	 */
	private void scanFile(File file, String absolutePath, String relativePath, boolean scanTimestampsOnly)
			throws IOException {
		lastModified = Math.max(lastModified, file.lastModified());
		if (!scanTimestampsOnly) {
			if (relativePath.endsWith(".class")) {
				// Found a classfile
				try (InputStream inputStream = new FileInputStream(file)) {
					// Inspect header of classfile
					readClassInfoFromClassfileHeader(inputStream);
				}
			} else {
				// For non-classfiles, match file paths against path patterns
				for (FilePathMatcher fileMatcher : filePathMatchers) {
					if (fileMatcher.pattern.matcher(relativePath).matches()) {
						// If there's a match, open the file as a stream and call the match processor
						try (InputStream inputStream = new FileInputStream(file)) {
							fileMatcher.fileMatchProcessor.processMatch(absolutePath, relativePath, inputStream);
						}
					}
				}
			}
		}
	}

	/**
	 * Scan a directory for matching file path patterns.
	 */
	private void scanDir(File dir, int ignorePrefixLen, boolean scanTimestampsOnly) throws IOException {
		String absolutePath = dir.getPath();
		String relativePath = ignorePrefixLen > absolutePath.length() ? "" : absolutePath.substring(ignorePrefixLen);
		if (File.separatorChar != '/') {
			// Fix scanning on Windows
			relativePath = relativePath.replace(File.separatorChar, '/');
		}
		boolean scanDirs = false, scanFiles = false;
		for (String pathToScan : pathsToScan) {
			if (relativePath.startsWith(pathToScan) || //
					pathToScan.equals("/") || (relativePath.length() == pathToScan.length() - 1 && //
					pathToScan.startsWith(relativePath))) {
				// In a path that has a whitelisted path as a prefix -- can start scanning files
				scanDirs = scanFiles = true;
				break;
			}
			if (pathToScan.startsWith(relativePath)) {
				// In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
				scanDirs = true;
			}
		}
		if (scanDirs || scanFiles) {
			lastModified = Math.max(lastModified, dir.lastModified());
			File[] subFiles = dir.listFiles();
			for (final File subFile : subFiles) {
				if (subFile.isDirectory()) {
					// Recurse into subdirectory
					scanDir(subFile, ignorePrefixLen, scanTimestampsOnly);
				} else if (scanFiles && subFile.isFile()) {
					// Scan file
					String leafSuffix = "/" + subFile.getName();
					scanFile(subFile, absolutePath + leafSuffix, relativePath + leafSuffix, scanTimestampsOnly);
				}
			}
		}
	}

	/**
	 * Scan a zipfile for matching file path patterns. (Does not recurse into
	 * zipfiles within zipfiles.)
	 */
	private void scanZipfile(final String zipfilePath, final ZipFile zipFile, long zipFileLastModified,
			boolean scanTimestampsOnly) throws IOException {
		boolean timestampWarning = false;
		for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
			// Scan for matching filenames
			final ZipEntry entry = entries.nextElement();
			if (!entry.isDirectory()) {
				// Only process file entries (zipfile indices contain both directory entries and
				// separate file entries for files within each directory, in lexicographic order)
				String path = entry.getName();
				boolean scanFile = false;
				for (String pathToScan : pathsToScan) {
					if (path.startsWith(pathToScan) || pathToScan.equals("/")) {
						// File path has a whitelisted path as a prefix -- can scan file
						scanFile = true;
						break;
					}
				}
				if (scanFile) {
					// If USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is true, use zipfile entry timestamps,
					// otherwise use the modification time of the zipfile itself. Using zipfile entry
					// timestamps assumes that the timestamp on zipfile entries was properly added, and
					// that the clock of the machine adding the zipfile entries is in sync with the 
					// clock used to timestamp regular file and directory entries in the current
					// classpath. USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is set to false by default,
					// as zipfile entry timestamps are less trustworthy than filesystem timestamps.
					long entryTime = USE_ZIPFILE_ENTRY_MODIFICATION_TIMES //
					? entry.getTime()
							: zipFileLastModified;
					lastModified = Math.max(lastModified, entryTime);
					if (entryTime > System.currentTimeMillis() && !timestampWarning) {
						String msg = zipfilePath + " contains modification timestamps after the current time";
						// Log.warning(msg);
						System.err.println(msg);
						// Only warn once
						timestampWarning = true;
					}
					if (!scanTimestampsOnly) {
						if (path.endsWith(".class")) {
							// Found a classfile, open it as a stream and inspect header
							try (InputStream inputStream = zipFile.getInputStream(entry)) {
								readClassInfoFromClassfileHeader(inputStream);
							}
						} else {
							// For non-classfiles, match file paths against path patterns
							for (FilePathMatcher fileMatcher : filePathMatchers) {
								if (fileMatcher.pattern.matcher(path).matches()) {
									// There's a match -- open the file as a stream and
									// call the match processor
									try (InputStream inputStream = zipFile.getInputStream(entry)) {
										fileMatcher.fileMatchProcessor.processMatch(path, path, inputStream);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Get a list of unique elements on the classpath (directories and files) as
	 * File objects, preserving order. Classpath elements that do not exist are
	 * not included in the list.
	 */
	public List<File> getUniqueClasspathElements() {
		String[] pathElements = System.getProperty("java.class.path").split(File.pathSeparator);
		HashSet<String> pathElementsSet = new HashSet<>();
		ArrayList<File> pathFiles = new ArrayList<>();
		for (String pathElement : pathElements) {
			if (pathElementsSet.add(pathElement)) {
				File file = new File(pathElement);
				if (file.exists()) {
					pathFiles.add(file);
				}
			}
		}
		return pathFiles;
	}

	/**
	 * Scan classpath for matching files. Call this after all match processors
	 * have been added.
	 */
	private void scan(boolean scanTimestampsOnly) {
		// long scanStart = System.currentTimeMillis();

		classesEncounteredSoFarDuringScan.clear();
		if (!scanTimestampsOnly) {
			classGraphBuilder.reset();
		}

		try {
			// Iterate through path elements and recursively scan within each directory and zipfile
			for (File pathElt : getUniqueClasspathElements()) {
				String path = pathElt.getPath();

				if (pathElt.isDirectory()) {
					// Scan within dir path element
					scanDir(pathElt, path.length() + 1, scanTimestampsOnly);
				} else if (pathElt.isFile()) {
					String pathLower = path.toLowerCase();
					if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
						// Scan within jar/zipfile path element
						try (ZipFile zipFile = new ZipFile(pathElt)){
						   scanZipfile(path, zipFile, pathElt.lastModified(), scanTimestampsOnly);						
						}
					} else {
						// File listed directly on classpath
						scanFile(pathElt, path, pathElt.getName(), scanTimestampsOnly);

						for (FilePathMatcher fileMatcher : filePathMatchers) {
							if (fileMatcher.pattern.matcher(path).matches()) {
								// If there's a match, open the file as a stream and call the
								// match processor
								try (InputStream inputStream = new FileInputStream(pathElt)) {
									fileMatcher.fileMatchProcessor.processMatch(path, pathElt.getName(), inputStream);
								}
							}
						}
					}
				} else {
					// Log.info("Skipping non-file/non-dir on classpath: " + file.getCanonicalPath());
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (!scanTimestampsOnly) {
			// Finalize class and interface DAGs
			classGraphBuilder.finalizeNodes();
			// Look for class and interface matches
			for (ClassMatcher classMatcher : classMatchers) {
				classMatcher.lookForMatches();
			}
		}
		// Log.info("Classpath " + (scanTimestampsOnly ? "timestamp " : "") + "scanning took: "
		//      + (System.currentTimeMillis() - scanStart) + " ms");
	}

	/**
	 * Scan classpath for matching files. Call this after all match processors
	 * have been added.
	 */
	public void scan() {
		scan(/* scanTimestampsOnly = */false);
	}

	/**
	 * Returns true if the classpath contents have been changed since scan() was
	 * last called. Only considers classpath prefixes whitelisted in the call to
	 * the constructor. Returns true if scan() has not yet been run.
	 */
	public boolean classpathContentsModifiedSinceScan() {
		long oldLastModified = this.lastModified;
		if (oldLastModified == 0) {
			return true;
		} else {
			scan(/* scanTimestampsOnly = */true);
			long newLastModified = this.lastModified;
			return newLastModified > oldLastModified;
		}
	}
}
