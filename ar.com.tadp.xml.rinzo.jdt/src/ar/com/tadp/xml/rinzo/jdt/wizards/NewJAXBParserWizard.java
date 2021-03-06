/*****************************************************************************
 * This file is part of Rinzo
 *
 * Author: Claudio Cancinos
 * WWW: https://sourceforge.net/projects/editorxml
 * Copyright (C): 2008, Claudio Cancinos
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; If not, see <http://www.gnu.org/licenses/>
 ****************************************************************************/
package ar.com.tadp.xml.rinzo.jdt.wizards;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.xml.sax.SAXParseException;

import ar.com.tadp.xml.rinzo.XMLEditorPlugin;
import ar.com.tadp.xml.rinzo.jdt.RinzoJDTPlugin;
import ar.com.tadp.xml.rinzo.jdt.Utils;

import com.sun.tools.xjc.Driver;
import com.sun.tools.xjc.XJCListener;

/**
 * Wizard to generate JAXB java classes binded to a schema definition plus a
 * JAXB parser to read and write from xml to objects and objects to xml
 * 
 * @author ccancinos
 */
public class NewJAXBParserWizard extends Wizard implements INewWizard {

	private IStructuredSelection selection;
	private NewJAXBParserWizardPage newParserPage;
	private Collection<String> schemaLocations;
	private BindingFilesWizardPage bindingPage;
	private String template;

	public NewJAXBParserWizard(Collection<String> schemaLocations) {
		this.schemaLocations = schemaLocations;
	}

	public void init(IWorkbench workbench, IStructuredSelection selection) {
		this.selection = selection;
	}

	@Override
	public void addPages() {
		this.newParserPage = new NewJAXBParserWizardPage(this.selection);
		this.bindingPage = new BindingFilesWizardPage("Create JAXB Parser", "Select JAXB binding files");
		addPage(this.newParserPage);
		addPage(this.bindingPage);
	}

	@Override
	public boolean performFinish() {
		try {
			Driver.run(this.createArgumentsList(), new XJCListenerExtension());

			this.refreshSourceFolder();
			this.createParser();
		} catch (Exception e) {
			MessageDialog.openError(this.getShell(), "Generate JAXB Parser", e.getLocalizedMessage());
			XMLEditorPlugin.log(e);
			return false;
		}

		return true;
	}

	private void refreshSourceFolder() throws CoreException {
		IWorkspaceRoot fWorkspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		Path path = new Path(this.newParserPage.getPackageFragmentRootText());
		fWorkspaceRoot.getFolder(path).refreshLocal(IResource.DEPTH_INFINITE, null);
	}

	private String[] createArgumentsList() {
		String sourceDirectory = this.newParserPage.getSourceDirectoryAbsolutePath();
		String packageName = this.newParserPage.getPackage();
		String encoding = this.newParserPage.getEncoding();
		String targetVersion = this.newParserPage.getTargetVersion();

		List<String> args = new ArrayList<String>();
		for (String fileLocation : this.schemaLocations) {
			args.add(fileLocation);
		}

		args.add("-no-header");
		args.add("-d");
		args.add(sourceDirectory);
		args.add("-p");
		args.add(packageName);

		args.add("-encoding");
		args.add(encoding);

		args.add("-target");
		args.add(targetVersion);

		args.add("-enableIntrospection");

		if (this.newParserPage.isFluentAPI()) {
			args.add("-Xfluent-api");
		}
		if (this.newParserPage.isValueConstructor()) {
			args.add("-Xvalue-constructor");
		}
		if (this.newParserPage.isDefaultValue()) {
			args.add("-Xdefault-value");
		}

		for (String bindingFile : this.bindingPage.getFiles()) {
			if (!Utils.isEmpty(bindingFile)) {
				args.add("-b");
				args.add(bindingFile);
			}
		}

		String[] arguments = args.toArray(new String[] {});
		return arguments;
	}

	private void createParser() throws JavaModelException {
		RootTypeFactoryMethod rootType = this.getRootType();
		String className = rootType.getRootType() + "Parser.java";

		IPackageFragment packageFragment = this.newParserPage.getPackageFragmentRoot().getPackageFragment(
				this.newParserPage.getPackage());

		String source = this.getParserSourceCode(packageFragment.getElementName(), rootType.getMethodName(),
				rootType.getRootType());

		packageFragment.createCompilationUnit(className, source, false, null);
	}

	private String getParserSourceCode(String packageName, String factoryMethodName, String rootType) {
		if (this.template == null) {
			this.template = this.readTemplate();
		}
		return this.template
				.replaceAll("__PACKAGE__", packageName)
				.replaceAll("__TYPE__", rootType)
				.replaceAll("__FACTORY_METHOD__", factoryMethodName);
	}

	private String readTemplate() {
		URL url;
		try {
			url = new URL("platform:/plugin/" + RinzoJDTPlugin.PLUGIN_ID + "/resources/jaxbParserTemplate.java");
			InputStream inputStream = url.openConnection().getInputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));
			String inputLine;

			StringBuffer buffer = new StringBuffer();
			while ((inputLine = in.readLine()) != null) {
				buffer.append(inputLine);
				buffer.append("\n");
			}
			in.close();
			return buffer.toString();
		} catch (IOException e) {
			XMLEditorPlugin.log(e);
		}
		return "";
	}

	private RootTypeFactoryMethod getRootType() {
		try {
			IMethod[] methods = this.newParserPage.getPackageFragmentRoot()
					.getPackageFragment(this.newParserPage.getPackage()).getCompilationUnit("ObjectFactory.java")
					.getType("ObjectFactory").getMethods();
			for (IMethod method : methods) {
				String returnType = method.getReturnType();
				if (returnType.contains("JAXBElement")) {
					String rootType = returnType.substring(returnType.indexOf("<"), returnType.lastIndexOf(">"));
					rootType = rootType.substring(rootType.indexOf("Q") + 1, rootType.lastIndexOf(";"));
					return new RootTypeFactoryMethod(method.getElementName(), rootType);
				}
			}
			for (IMethod method : methods) {
				String returnType = method.getReturnType();
				if (!returnType.contains(".") && method.getElementName().startsWith("create")) {
					String rootType = returnType.substring(returnType.indexOf("Q") + 1, returnType.lastIndexOf(";"));
					return new RootTypeFactoryMethod(method.getElementName(), rootType);
				}
			}

		} catch (JavaModelException e) {
			XMLEditorPlugin.log(e);
		}
		return null;
	}

	private final class RootTypeFactoryMethod {
		private String methodName;
		private String rootType;

		public RootTypeFactoryMethod(String methodName, String rootType) {
			this.methodName = methodName;
			this.rootType = rootType;
		}

		public String getMethodName() {
			return methodName;
		}

		public String getRootType() {
			return rootType;
		}

	}

	private final class XJCListenerExtension extends XJCListener {
		private MessageConsole console;
		private MessageConsoleStream stream;

		public XJCListenerExtension() {
			console = this.getConsole();
			stream = console.newMessageStream();
		}

		private MessageConsole getConsole() {
			IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
			for (IConsole consol : manager.getConsoles()) {
				if (consol.getName().equals(RinzoJDTPlugin.PLUGIN_ID)) {
					MessageConsole msgConsole = (MessageConsole) consol;
					msgConsole.clearConsole();
					return msgConsole;
				}
			}
			MessageConsole messageConsole = new MessageConsole(RinzoJDTPlugin.PLUGIN_ID, null);
			messageConsole.activate();
			manager.addConsoles(new IConsole[] { messageConsole });
			return messageConsole;
		}

		public void warning(SAXParseException arg0) {
			stream.println("warning: " + arg0);
		}

		public void info(SAXParseException arg0) {
			stream.println("info: " + arg0);
		}

		public void fatalError(SAXParseException arg0) {
			stream.println("fatal error: " + arg0);
		}

		public void error(SAXParseException arg0) {
			stream.println("error: " + arg0);
		}
	}

}
