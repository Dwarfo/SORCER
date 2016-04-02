/*
 * Copyright 2009 the original author or authors.
 * Copyright 2009 SorcerSoft.org.
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
package sorcer.eo;

import groovy.lang.Closure;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceTemplate;
import net.jini.core.transaction.Transaction;
import net.jini.core.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.co.operator.DataEntry;
import sorcer.co.tuple.*;
import sorcer.core.Name;
import sorcer.core.SorcerConstants;
import sorcer.core.context.*;
import sorcer.core.context.model.QueueStrategy;
import sorcer.core.context.model.ent.EntModel;
import sorcer.core.context.model.ent.Entry;
import sorcer.core.context.model.ent.EntryList;
import sorcer.core.context.model.par.Par;
import sorcer.core.context.model.par.ParModel;
import sorcer.core.context.model.srv.Srv;
import sorcer.core.context.model.srv.SrvModel;
import sorcer.core.deploy.ServiceDeployment;
import sorcer.core.dispatch.SortingException;
import sorcer.core.dispatch.SrvModelAutoDeps;
import sorcer.core.exertion.*;
import sorcer.core.invoker.InvokeIncrementor;
import sorcer.core.plexus.Morpher;
import sorcer.core.plexus.MultiFidelity;
import sorcer.core.provider.*;
import sorcer.core.provider.exerter.Binder;
import sorcer.core.provider.exerter.ServiceShell;
import sorcer.core.provider.rendezvous.ServiceConcatenator;
import sorcer.core.provider.rendezvous.ServiceModeler;
import sorcer.core.requestor.ExertRequestor;
import sorcer.core.signature.*;
import sorcer.service.Signature.ReturnPath;
import sorcer.netlet.ScriptExerter;
import sorcer.service.*;
import sorcer.service.Path;
import sorcer.service.Signature.*;
import sorcer.service.Strategy.*;
import sorcer.service.modeling.Model;
import sorcer.service.modeling.Modeling;
import sorcer.service.modeling.ModelingTask;
import sorcer.service.modeling.Variability;
import sorcer.util.Loop;
import sorcer.util.ObjectCloner;
import sorcer.util.Sorcer;
import sorcer.util.url.sos.SdbUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.*;

import static sorcer.co.operator.rasis;
import static sorcer.co.operator.srv;
import static sorcer.mo.operator.entModel;
import static sorcer.mo.operator.srvModel;
import static sorcer.po.operator.parModel;

/**
 * Operators defined for the Service Modeling Language (SML).
 *
 * @author Mike Sobolewski
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class operator {

	protected static int count = 0;

	protected static final Logger logger = LoggerFactory.getLogger(operator.class.getName());

	public static void requestTime(Exertion exertion) {
		((ServiceExertion) exertion).setExecTimeRequested(true);
	}

	public static ExertRequestor requestor(Class requestorType, String... args) {
		return  new ExertRequestor(requestorType, args);
	}

	public static String path(List<String> attributes) {
		if (attributes.size() == 0)
			return null;
		if (attributes.size() > 1) {
			StringBuilder spr = new StringBuilder();
			for (int i = 0; i < attributes.size() - 1; i++) {
				spr.append(attributes.get(i)).append(SorcerConstants.CPS);
			}
			spr.append(attributes.get(attributes.size() - 1));
			return spr.toString();
		}
		return attributes.get(0);
	}

	public static Object revalue(Context evaluation, String path,
								 Arg... entries) throws ContextException {
		Object obj = value(evaluation, path, entries);
		if (obj instanceof Evaluation) {
			obj = value((Evaluation) obj, entries);
		}
		return obj;
	}

	public static Object revalue(Object object, String path,
								 Arg... entries) throws ContextException {
		Object obj = null;
		if (object instanceof Evaluation || object instanceof Context) {
			obj = value((Evaluation) object, path, entries);
			obj = value((Evaluation) obj, entries);
		} else if (object instanceof Context) {
			obj = value((Context) object, path, entries);
			obj = value((Context) obj, entries);
		} else {
			obj = object;
		}
		return obj;
	}

	public static Object revalue(Object object, Arg... entries)
			throws EvaluationException {
		Object obj = null;
		if (object instanceof Evaluation) {
			obj = value((Evaluation) object, entries);
		} else if (object instanceof Context) {
			try {
				obj = value((Context) object, entries);
			} catch (ContextException e) {
				throw new EvaluationException(e);
			}
		}
		if (obj == null) {
			obj = object;
		}
		return obj;
	}

	public static Path path(String path) {
		return new Path(path);
	}

	public static Path path(String path, Object info) {
		return new Path(path, info);
	}

	public static String attPath(String... attributes) {
		if (attributes.length == 0)
			return null;
		if (attributes.length > 1) {
			StringBuilder spr = new StringBuilder();
			for (int i = 0; i < attributes.length - 1; i++) {
				spr.append(attributes[i]).append(SorcerConstants.CPS);
			}
			spr.append(attributes[attributes.length - 1]);
			return spr.toString();
		}
		return attributes[0];
	}

	public static <T> Complement<T> subject(String path, T value)
			throws SignatureException {
		return new Complement<T>(path, value);
	}

	public static void add(Exertion exertion, Identifiable... entries)
			throws ContextException, RemoteException {
		add(exertion.getContext(), entries);
	}

	public static void put(Exertion exertion, Identifiable... entries)
			throws ContextException, RemoteException {
		put(exertion.getContext(), entries);
	}

	public static Exertion setContext(Exertion exertion, Context context) {
		((ServiceExertion) exertion).setContext(context);
		return exertion;
	}

	public static ControlContext control(Exertion exertion)
			throws ContextException {
		return ((ServiceExertion) exertion).getControlContext();
	}

	public static ControlContext control(Exertion exertion, String childName)
			throws ContextException {
		return (ControlContext) ((Exertion) exertion.getMogram(childName)).getControlContext();
	}

	public static Context cxt(Object... entries) throws ContextException {
		return context(entries);
	}

	public static Context cxt(Mogram exertion) throws ContextException {
		return context(exertion);
	}

	public static Context ccxt(Exertion exertion) throws ContextException {
		return ((ServiceExertion) exertion).getControlContext();
	}

	public static Context upcxt(Exertion mogram) throws ContextException {
		return upcontext(mogram);
	}

	public static Context upcontext(Mogram mogram) throws ContextException {
		if (mogram instanceof CompoundExertion)
			return mogram.getContext();
		else
			return  mogram.getDataContext();
	}

	public static Context taskContext(String path, Exertion service) throws ContextException {
		if (service instanceof ServiceExertion) {
			return ((CompoundExertion) service).getComponentContext(path);
		} else
			throw new ContextException("Service not an exertion: " + service);
	}

	public static Context subcontext(Context context, List<Path> paths) throws ContextException {
		Path[] pl = new Path[paths.size()];
		return context.getDirectionalSubcontext(paths.toArray(pl));
	}

	public static Context subcontext(Context context, Path... paths) throws ContextException {
		return context.getDirectionalSubcontext(paths);
	}

	public static Context scope(Object... entries) throws ContextException {
		Object[] args = new Object[entries.length + 1];
		System.arraycopy(entries, 0, args, 1, entries.length);
		args[0] = Context.Type.SCOPE;
		return context(args);
	}

	public static Context context(Object... entries)
			throws ContextException {
		// do not create a context from Context, jut return
		if (entries.length == 1 && entries[0] instanceof Context)
			return  (Context)entries[0];

		Context cxt = null;
		List<MapContext> connList = new ArrayList<MapContext>();
		Strategy.Access accessType = null;
		Strategy.Flow flowType = null;

		if (entries[0] instanceof Exertion) {
			Exertion xrt = (Exertion) entries[0];
			if (entries.length >= 2 && entries[1] instanceof String)
				xrt = (Exertion) (xrt).getComponentMogram((String) entries[1]);
			return xrt.getDataContext();
		} else if (entries[0] instanceof Link) {
			return ((Link) entries[0]).getContext();
		} else if (entries.length == 1 && entries[0] instanceof String) {
			return new PositionalContext((String) entries[0]);
		} else if (entries.length == 2 && entries[0] instanceof String
				&& entries[1] instanceof Exertion) {
			return ((Exertion) ((CompoundExertion) entries[1]).getComponentMogram(
					(String) entries[0])).getContext();
		} else if (entries[0] instanceof Context && entries[1] instanceof List) {
			return ((ServiceContext) entries[0]).getDirectionalSubcontext(Path.getSigPaths((List)entries[1]));
		} else if (entries[0] instanceof Model) {
			cxt = (PositionalContext) entries[0];
		} else {
			cxt = getPersistedContext(entries);
			if (cxt != null) return cxt;
		}
		String name = getUnknown();
		List<Tuple2<String, ?>> entryList = new ArrayList<Tuple2<String, ?>>();
		List<Par> parEntryList = new ArrayList<Par>();
		List<Context.Type> types = new ArrayList<Context.Type>();
		List<EntryList> entryLists = new ArrayList<EntryList>();
		List<DependencyEntry> depList = new ArrayList<DependencyEntry>();
		Complement subject = null;
		ReturnPath returnPath = null;
		ExecPath execPath = null;
		Args cxtArgs = null;
		ParameterTypes parameterTypes = null;
		PathResponse response = null;
		QueueStrategy modelStrategy = null;
		Signature sig = null;
		Class customContextClass = null;
		boolean manualDeps = false;
		for (Object o : entries) {
			if (o instanceof Complement) {
				subject = (Complement) o;
			} else if (o instanceof Args) {
//					&& ((Args) o).args.getClass().isArray()) {
				cxtArgs = (Args) o;
			} else if (o instanceof ParameterTypes)  {
//					&& ((ParameterTypes) o).parameterTypes.getClass().isArray()) {
				parameterTypes = (ParameterTypes) o;
			} else if (o instanceof PathResponse) {
				response = (PathResponse) o;
			} else if (o instanceof ReturnPath) {
				returnPath = (ReturnPath) o;
			} else if (o instanceof ExecPath) {
				execPath = (ExecPath) o;
			} else if (o instanceof Par) {
				parEntryList.add((Par) o);
			} else if (o instanceof Tuple2) {
				entryList.add((Tuple2) o);
			} else if (o instanceof Context.Type) {
				types.add((Context.Type) o);
			} else if (o instanceof String) {
				name = (String) o;
			} else if (o instanceof EntryList) {
				entryLists.add((EntryList) o);
			} else if (o instanceof MapContext) {
				connList.add((MapContext) o);
			} else if (o instanceof DependencyEntry) {
				depList.add((DependencyEntry) o);
			} else if (o instanceof Signature) {
				sig = (Signature) o;
			} else if (o instanceof Class) {
				customContextClass = (Class) o;
			} else if (Strategy.Flow.EXPLICIT.equals(o)) {
				manualDeps = true;
			} else if (o instanceof Strategy.Access) {
				accessType = (Strategy.Access)o;
			} else if (o instanceof Strategy.Access) {
				flowType = (Strategy.Flow)o;
			}
		}

		if (cxt == null) {
			if (types.contains(Context.Type.ARRAY)) {
				if (subject != null)
					cxt = new ArrayContext(name, subject.path(), subject.value());
				else
					cxt = new ArrayContext(name);
			} else if (types.contains(Context.Type.LIST)) {
				if (subject != null)
					cxt = new ListContext(name, subject.path(), subject.value());
				else
					cxt = new ListContext(name);
			} else if (types.contains(Context.Type.SCOPE)) {
				cxt = new ScopeContext(name);
			} else if (types.contains(Context.Type.SHARED)
					&& types.contains(Context.Type.INDEXED)) {
				cxt = new SharedIndexedContext(name);
			} else if (types.contains(Context.Type.SHARED)) {
				cxt = new SharedAssociativeContext(name);
			} else if (types.contains(Context.Type.ASSOCIATIVE)) {
				if (subject != null)
					cxt = new ServiceContext(name, subject.path(), subject.value());
				else
					cxt = new ServiceContext(name);
			} else if (customContextClass != null) {
				try {
					cxt = (Context) customContextClass.newInstance();
				} catch (Exception e) {
					throw new ContextException(e);
				}
				if (subject != null)
					cxt.setSubject(subject.path(), subject.value());
				else
					cxt.setName(name);
			} else {
				if (subject != null) {
					cxt = new PositionalContext(name, subject.path(),
							subject.value());
				} else {
					cxt = new PositionalContext(name);
				}
			}
		}


		if (cxt instanceof PositionalContext) {
			PositionalContext pcxt = (PositionalContext) cxt;
			if (entryList.size() > 0)
				popultePositionalContext(pcxt, entryList);
		} else {
			if (entryList.size() > 0)
				populteContext(cxt, entryList);
		}
		if (parEntryList.size() > 0) {
			for (Par p : parEntryList)
				cxt.putValue(p.getName(), p);
		}
		if (returnPath != null)
			((ServiceContext) cxt).setReturnPath(returnPath);
		if (execPath != null)
			((ServiceContext) cxt).setExecPath(execPath);
		if (cxtArgs != null) {
			if (cxtArgs.path() != null) {
				((ServiceContext) cxt).setArgsPath(cxtArgs.path());
			} else {
				((ServiceContext) cxt).setArgsPath(Context.PARAMETER_VALUES);
			}
			((ServiceContext) cxt).setArgs(cxtArgs.args);
		}
		if (parameterTypes != null) {
			if (parameterTypes.path() != null) {
				((ServiceContext) cxt).setParameterTypesPath(parameterTypes
						.path());
			} else {
				((ServiceContext) cxt)
						.setParameterTypesPath(Context.PARAMETER_TYPES);
			}
			((ServiceContext) cxt)
					.setParameterTypes(parameterTypes.parameterTypes);
		}
		if (response != null) {
			if (response.path() != null) {
				((ServiceContext) cxt).getMogramStrategy().getResponsePaths().add(new Name(response.path()));
			}
			((ServiceContext) cxt).getMogramStrategy().setResult(response.path(), response.target);
		}
		if (entryLists.size() > 0) {
			((ServiceContext) cxt).setEntryLists(entryLists);
		}
		if (connList.size() > 0) {
			for (MapContext conn : connList) {
				if (conn.direction == MapContext.Direction.IN) {
					((ServiceContext) cxt).getMogramStrategy().setInConnector(conn);
				} else {
					((ServiceContext) cxt).getMogramStrategy().setOutConnector(conn);
				}
			}
		}
		if (depList.size() > 0) {
			Map<String, List<Path>> dm = ((ServiceContext) cxt).getMogramStrategy().getDependentPaths();
			String path = null;
			List<Path> dependentPaths = null;
			for (DependencyEntry e : depList) {
				path = e.getName();
				dependentPaths = e.value();
				dm.put(path, dependentPaths);
			}
		}
		if (accessType != null)
			cxt.getMogramStrategy().setAccessType(accessType);
		if (flowType != null)
			cxt.getMogramStrategy().setFlowType(flowType);
		if (sig != null)
			cxt.setSubject(sig.getSelector(), sig.getServiceType());
		if (cxt instanceof SrvModel && ! manualDeps) {
			try {
				cxt = new SrvModelAutoDeps((SrvModel) cxt).get();
			} catch (SortingException e) {
				throw new ContextException("Problem with dependencies: " + e);
			}
		}
		return cxt;
	}

	private static Context getPersistedContext(Object... entries) throws ContextException {
		ServiceContext cxt = null;
		try {
			if (entries.length == 1 && SdbUtil.isSosURL(entries[0]))
				cxt = (ServiceContext) ((URL) entries[0]).getContent();
			else if (entries.length == 2 && entries[0] instanceof String && SdbUtil.isSosURL(entries[1])) {
				cxt = (ServiceContext) ((URL) entries[1]).getContent();
				cxt.setName((String) entries[0]);
			}
		} catch (IOException e) {
			throw new ContextException(e);
		}
		return cxt;
	}

	protected static void popultePositionalContext(PositionalContext pcxt,
												   List<Tuple2<String, ?>> entryList) throws ContextException {
		for (int i = 0; i < entryList.size(); i++) {
			Tuple2 t = entryList.get(i);
			if (t instanceof Srv) {
				try {
					if (t.asis() instanceof Scopable) {
						if (((Scopable) t.value()).getScope() != null)
							((Scopable)((Scopable) t.value()).getScope()).setScope(pcxt);
						else
							((Scopable) t.value()).setScope(pcxt);
					}
				} catch (RemoteException e) {
					throw new ContextException(e);
				}
				pcxt.putInoutValueAt(t.path(), t, i + 1);
			} else if (t instanceof InputEntry) {
				Object par = t.value();
				if (par instanceof Scopable) {
					((Scopable) par).setScope(pcxt);
				}
				if (t.isPersistent()) {
					setPar(pcxt, t, i);
				} else {
					pcxt.putInValueAt(t.path(), t.value(), i + 1);
				}
			} else if (t instanceof OutputEntry) {
				if (t.isPersistent()) {
					setPar(pcxt, t, i);
				} else {
					pcxt.putOutValueAt(t.path(), t.value(), i + 1);
				}
			} else if (t instanceof InoutEntry) {
				if (t.isPersistent()) {
					setPar(pcxt, t, i);
				} else {
					pcxt.putInoutValueAt(t.path(), t.value(), i + 1);
				}
			} else if (t instanceof Entry) {
				if (t.isPersistent()) {
					setPar(pcxt, (Entry) entryList.get(i), i);
				} else {
					if (t.value() instanceof Scopable) {
						((Scopable) t.value()).setScope(pcxt);
					}
					pcxt.putValueAt(t.path(), t.value(), i + 1);
				}
			} else if (t instanceof DataEntry) {
				pcxt.putValueAt(Context.DSD_PATH, t.value(), i + 1);
			}
		}
	}

	public static void populteContext(Context cxt,
									  List<Tuple2<String, ?>> entryList) throws ContextException {
		for (int i = 0; i < entryList.size(); i++) {
			if (entryList.get(i) instanceof InputEntry) {
				Object val = null;
				try {
					val = entryList.get(i).asis();
				} catch (RemoteException e) {
					throw new ContextException(e);
				}
				if (val instanceof Incrementor &&
						((InvokeIncrementor)val).getTarget() == null) {
					((InvokeIncrementor)val).setScope(cxt);
				}
				if (((InputEntry) entryList.get(i)).isPersistent()) {
					setPar(cxt, (InputEntry) entryList.get(i));
				} else {
					cxt.putInValue(((Entry) entryList.get(i)).path(),
							((Entry) entryList.get(i)).value());
				}
			} else if (entryList.get(i) instanceof OutputEntry) {
				if (((OutputEntry) entryList.get(i)).isPersistent()) {
					setPar(cxt, (OutputEntry) entryList.get(i));
				} else {
					cxt.putOutValue(((Entry) entryList.get(i)).path(),
							((Entry) entryList.get(i)).value());
				}
			} else if (entryList.get(i) instanceof InoutEntry) {
				if (((InoutEntry) entryList.get(i)).isPersistent()) {
					setPar(cxt, (InoutEntry) entryList.get(i));
				} else {
					cxt.putInoutValue(((Entry) entryList.get(i)).path(),
							((Entry) entryList.get(i)).value());
				}
			} else if (entryList.get(i) instanceof Entry) {
				if (((Entry) entryList.get(i)).isPersistent()) {
					setPar(cxt, (Entry) entryList.get(i));
				} else {
					cxt.putValue(((Entry) entryList.get(i)).path(),
							((Entry) entryList.get(i)).value());
				}
			} else if (entryList.get(i) instanceof DataEntry) {
				cxt.putValue(Context.DSD_PATH,
						((Entry) entryList.get(i)).value());
			}
		}
	}

	public Context rm(Model model, String path) {
		return remove(model, path);
	}

	public Context remove(Model model, String path) {
		ServiceContext context = (ServiceContext) model;
		context.getData().remove(path);
		return context;
	}

	public static Context add(Model model, Identifiable... objects)
			throws RemoteException, ContextException {
		boolean isReactive = false;
		Context context = (Context) model;
		for (Identifiable i : objects) {
			if (i instanceof Reactive && ((Reactive) i).isReactive()) {
				isReactive = true;
			}
			if (i instanceof Mogram) {
				((Mogram) i).setScope((Context)model);
				i = srv(i);
			}
			if (context instanceof PositionalContext) {
				PositionalContext pc = (PositionalContext) context;
				if (i instanceof InputEntry) {
					if (isReactive) {
						pc.putInValueAt(i.getName(), i, pc.getTally() + 1);
					} else {
						pc.putInValueAt(i.getName(), ((Entry) i).value(), pc.getTally() + 1);
					}
				} else if (i instanceof OutputEntry) {
					if (isReactive) {
						pc.putOutValueAt(i.getName(), i, pc.getTally() + 1);
					} else {
						pc.putOutValueAt(i.getName(), ((Entry) i).value(), pc.getTally() + 1);
					}
				} else if (i instanceof InoutEntry) {
					if (isReactive) {
						pc.putInoutValueAt(i.getName(), i, pc.getTally() + 1);
					} else {
						pc.putInoutValueAt(i.getName(), ((Entry) i).value(), pc.getTally() + 1);
					}
				} else {
					if (model instanceof EntModel || isReactive) {
						pc.putValueAt(i.getName(), i, pc.getTally() + 1);
					} else {
						pc.putValueAt(i.getName(), ((Entry) i).value(), pc.getTally() + 1);
					}
				}
			} else if (context instanceof ServiceContext) {
				if (i instanceof InputEntry) {
					if (i instanceof Reactive) {
						context.putInValue(i.getName(), i);
					} else {
						context.putInValue(i.getName(), ((Entry) i).value());
					}
				} else if (i instanceof OutputEntry) {
					if (isReactive) {
						context.putOutValue(i.getName(), i);
					} else {
						context.putOutValue(i.getName(), ((Entry) i).value());
					}
				} else if (i instanceof InoutEntry) {
					if (isReactive) {
						context.putInoutValue(i.getName(), i);
					} else {
						context.putInoutValue(i.getName(), ((Entry) i).value());
					}
				} else {
					if (isReactive) {
						context.putValue(i.getName(), i);
					} else {
						context.putValue(i.getName(), ((Entry) i).value());
					}
				}
			} else {
				context.putValue(i.getName(), i);
			}
			if (i instanceof Entry) {
				Entry e = (Entry) i;
				if (e.isAnnotated()) context.mark(e.path(), e.annotation().toString());
				if (e.asis() instanceof Scopable) {
					((Scopable) e.asis()).setScope(context);
				}
			}
		}
		return context;
	}

	public static Context put(Context context, String path, Object value)
			throws ContextException {
		try {
			Object val = context.asis(path);
			if (val instanceof Par && ((Par)val).isPersistent())
				val = ((Par)val).asis();
			if (SdbUtil.isSosURL(val)) {
				SdbUtil.update((URL) val, value);
			} else {
				context.putValue(path, value);
			}
		} catch (Exception e) {
			throw new ContextException(e);
		}
		return context;
	}

	public static Context put(Model model, Identifiable... objects)
			throws RemoteException, ContextException {
		return put((Context) model, objects);
	}

	public static Context put(Context context, Identifiable... objects)
			throws RemoteException, ContextException {
		for (Identifiable i : objects) {
			// just replace the value
			if (((ServiceContext) context).containsPath(i.getName())) {
				context.putValue(i.getName(), i);
				continue;
			}

			if (context instanceof PositionalContext) {
				PositionalContext pc = (PositionalContext) context;
				if (i instanceof InputEntry) {
					pc.putInValueAt(i.getName(), i, pc.getTally() + 1);
				} else if (i instanceof OutputEntry) {
					pc.putOutValueAt(i.getName(), i, pc.getTally() + 1);
				} else if (i instanceof InoutEntry) {
					pc.putInoutValueAt(i.getName(), i, pc.getTally() + 1);
				} else {
					pc.putValueAt(i.getName(), i, pc.getTally() + 1);
				}
			} else if (context instanceof ServiceContext) {
				if (i instanceof InputEntry) {
					context.putInValue(i.getName(), i);
				} else if (i instanceof OutputEntry) {
					context.putOutValue(i.getName(), i);
				} else if (i instanceof InoutEntry) {
					context.putInoutValue(i.getName(), i);
				} else {
					context.putValue(i.getName(), i);
				}
			} else {
				context.putValue(i.getName(), i);
			}
			if (i instanceof Entry) {
				Entry e = (Entry) i;
				if (e.isAnnotated()) context.mark(e.path(), e.annotation().toString());
				if (e.asis() instanceof Scopable) {
					((Scopable) e.asis()).setScope(context);
				}
			}
		}
		return context;
	}

	protected static void setPar(PositionalContext pcxt, Tuple2 entry, int i)
			throws ContextException {
		Par p = new Par(entry.path(), entry.value());
		p.setPersistent(true);
		if (entry instanceof InputEntry)
			pcxt.putInValueAt(entry.path(), p, i + 1);
		else if (entry instanceof OutputEntry)
			pcxt.putOutValueAt(entry.path(), p, i + 1);
		else if (entry instanceof InoutEntry)
			pcxt.putInoutValueAt(entry.path(), p, i + 1);
		else
			pcxt.putValueAt(entry.path(), p, i + 1);
	}

	protected static void setPar(Context cxt, Tuple2 entry)
			throws ContextException {
		Par p = new Par(entry.path(), entry.value());
		p.setPersistent(true);
		if (entry instanceof InputEntry)
			cxt.putInValue(entry.path(), p);
		else if (entry instanceof OutputEntry)
			cxt.putOutValue(entry.path(), p);
		else if (entry instanceof InoutEntry)
			cxt.putInoutValue(entry.path(), p);
		else
			cxt.putValue(entry.path(), p);
	}

	public static List<String> names(List<? extends Identifiable> list) {
		List<String> names = new ArrayList<String>(list.size());
		for (Identifiable i : list) {
			names.add(i.getName());
		}
		return names;
	}

	public static String name(Object identifiable) {
		if (identifiable instanceof Identifiable)
			return ((Identifiable) identifiable).getName();
		else
			return null;
	}

	public static List<String> names(Identifiable... array) {
		List<String> names = new ArrayList<String>(array.length);
		for (Identifiable i : array) {
			names.add(i.getName());
		}
		return names;
	}

	public static List<Entry> attributes(Entry... entries) {
		List<Entry> el = new ArrayList<Entry>(entries.length);
		for (Entry e : entries)
			el.add(e);
		return el;
	}

	/**
	 * Returns the Evaluation with a realized substitution for its arguments.
	 *
	 * @param model
	 * @param entries
	 * @return an evaluation with a realized substitution
	 * @throws EvaluationException
	 * @throws RemoteException
	 */
	public static Object bind(Object model, Arg... entries)
			throws ContextException {
		if (model instanceof Substitutable) {
			Binder binder = new Binder((Mogram) model);
			binder.bind(entries);
		}
		return model;
	}

	public static Class type(Signature signature) {
		return signature.getServiceType();
	}

	public static String selector(Signature signature) {
		return signature.getSelector();
	}

	public static Signature sig(String name, String operation, Class serviceType)
			throws SignatureException {
		ServiceSignature signature = (ServiceSignature) sig(operation, serviceType, new Arg[]{});
		signature.setName(name);
		return signature;
	}

	public static Signature sig(String operation, Class serviceType)
			throws SignatureException {
		return sig(operation, serviceType, new Arg[]{});
	}

	public static Signature matchSigs(Signature signature, Class... matchTypes)
			throws SignatureException {
		Class[] types = matchTypes;
		if (signature.getServiceType() != null) {
			types = Arrays.copyOf(matchTypes, matchTypes.length+1);
			types[matchTypes.length] = signature.getServiceType();
		}
		((ServiceSignature)signature).setMatchTypes(types);
		return signature;
	}

	public static ReturnPath returnPath(Arg... args) {
		for (Arg a : args) {
			if (a instanceof ReturnPath)
				return (ReturnPath) a;
		}
		return null;
	}

	public static Signature sig(Class serviceType, String initSelector) throws SignatureException {
		try {
			Method selectorMethod = serviceType.getDeclaredMethod(initSelector, Context.class);
			if (!Modifier.isStatic(selectorMethod.getModifiers()))
				return sig(initSelector, serviceType);
		} catch (NoSuchMethodException e) {
			// skip
		}
		return sig(initSelector, serviceType, initSelector);
	}

	public static Signature sig(String operation, Class serviceType,
								String initSelector) throws SignatureException {
		try {
			return new ObjectSignature(operation, serviceType, initSelector,
					(Class<?>[]) null, (Object[]) null);
		} catch (Exception e) {
			throw new SignatureException(e);
		}
	}

	public static Signature sig(Class serviceType, Arg... args) throws SignatureException {
		if (args == null || args.length == 0)
			return defaultSig(serviceType);
		else
			return sig("?", serviceType, args);
	}

	public static Signature sig(String operation, Object provider, Arg... args) throws SignatureException {
		ObjectSignature sig = new ObjectSignature();
		sig.setName(operation);
		sig.setSelector(operation);
		sig.setTarget(provider);
		if (args.length > 0) {
			for (Object o : args) {
				if (o instanceof Type) {
					sig.setType((Type) o);
				} else if (o instanceof Operating) {
					sig.setActive((Operating) o);
				} else if (o instanceof Strategy.Shell) {
					sig.setShellRemote((Strategy.Shell) o);
				} else if (o instanceof ReturnPath) {
					sig.setReturnPath((ReturnPath) o);
				} else if (o instanceof ServiceDeployment) {
					sig.setProvisionable(true);
					sig.setDeployment((ServiceDeployment) o);
				}
			}
		}
		return sig;
	}

	public static Signature sig(String name, String operation, Class serviceType, Arg... args) throws SignatureException {
		ServiceSignature s = (ServiceSignature) sig(operation, serviceType, args);
		s.setName(name);
		return s;
	}

    public static Signature sig(String operation, Class serviceType, Arg... args) throws SignatureException {
        return sigActualName(true, operation, serviceType, args);
    }

        public static Signature sigActualName(boolean callActualName, String operation, Class serviceType, Arg... args) throws SignatureException {
		String providerName = null;
		Provision p = null;
		List<MapContext> connList = new ArrayList<MapContext>();
		if (args != null) {
			for (Object o : args) {
				if (o instanceof ProviderName) {
                    if (callActualName) {
                        providerName = Sorcer.getActualName(((ProviderName) o).getName());
                    } else {
                        providerName = ((ProviderName) o).getName();
                    }
				} else if (o instanceof Provision) {
					p = (Provision) o;
				} else if (o instanceof MapContext) {
					connList.add(((MapContext) o));
				}
			}
		}
		Signature sig = null;
//		if (Modeler.class.isAssignableFrom(serviceType)) {
//			sig = new ModelSignature(operation, serviceType, providerName, args);
//		} else
		if (serviceType.isInterface()) {
			sig = new NetSignature(operation, serviceType, providerName);
		} else {
			sig = new ObjectSignature(operation, serviceType);
			sig.setProviderName(providerName);
		}
		((ServiceSignature) sig).setName(operation);

		if (connList != null) {
			for (MapContext conn : connList) {
				if (conn.direction == MapContext.Direction.IN)
					((ServiceSignature) sig).setInConnector(conn);
				else
					((ServiceSignature) sig).setOutConnector(conn);
			}
		}

		if (p != null)
			((ServiceSignature) sig).setProvisionable(p);

		if (args.length > 0) {
			for (Object o : args) {
				if (o instanceof Type) {
					sig.setType((Type) o);
				} else if (o instanceof Operating) {
					((ServiceSignature) sig).setActive((Operating) o);
				} else if (o instanceof Provision) {
					((ServiceSignature) sig).setProvisionable((Provision) o);
				} else if (o instanceof Strategy.Shell) {
					((ServiceSignature) sig).setShellRemote((Strategy.Shell) o);
				} else if (o instanceof ReturnPath) {
					sig.setReturnPath((ReturnPath) o);
				} else if (o instanceof In ) {
					if (sig.getReturnPath() == null) {
						sig.setReturnPath(new ReturnPath((In) o));
					} else {
						((ReturnPath)sig.getReturnPath()).inPaths = ((In) o).getSigPaths();
					}
				} else if (o instanceof Out) {
					if (sig.getReturnPath() == null) {
						sig.setReturnPath(new ReturnPath((Out) o));
					} else {
						((ReturnPath)sig.getReturnPath()).outPaths = ((Out) o).getSigPaths();
					}
				} else if (o instanceof ServiceDeployment) {
					((ServiceSignature) sig).setProvisionable(true);
					((ServiceSignature) sig).setDeployment((ServiceDeployment) o);
				} else if (o instanceof Version && sig instanceof NetSignature) {
					((NetSignature) sig).setVersion(((Version) o).getName());
				}
			}
		}

		return sig;
	}

	public static String property(String property) {
		return System.getProperty(property);
	}

	public static String home() {
		return Sorcer.getHome();
	}

	public static ProviderName prvName(String name) {
		return new ProviderName(name);
	}

	public static String actualName(String name) {
		return Sorcer.getActualName(name);
	}

	public static Signature sig(String selector) throws SignatureException {
		return new ServiceSignature(selector);
	}

	public static Signature sig(String name, String selector)
			throws SignatureException {
		return new ServiceSignature(name, selector);
	}

	public static Signature sig(String name, String selector, ServiceDeployment deployment)
			throws SignatureException {
		ServiceSignature signture = new ServiceSignature(name, selector);
		signture.setDeployment(deployment);
		signture.setProvisionable(true);
		return signture;
	}

	public static Signature defaultSig(Class<?> serviceType) throws SignatureException {
		if (Modeling.class.isAssignableFrom(serviceType)) {
			return sig("evaluate", serviceType);
		} else if (Service.class.isAssignableFrom(serviceType)) {
			return sig("exert", serviceType);
		} else {
			return sig(serviceType, (ReturnPath) null);
		}
	}

	public static Signature sig(Class<?> serviceType, ReturnPath returnPath, ServiceDeployment deployment)
			throws SignatureException {
		Signature signature = sig(serviceType, returnPath);
		((ServiceSignature) signature).setDeployment(deployment);
		((ServiceSignature) signature).setProvisionable(true);
		return signature;
	}

	public static Signature sig(Class<?> serviceType, ReturnPath returnPath)
			throws SignatureException {
		Signature sig = null;
		if (serviceType.isInterface()) {
			sig = new NetSignature("exert", serviceType);
		} else if (Executor.class.isAssignableFrom(serviceType)) {
			sig = new ObjectSignature("exert", serviceType);
		} else {
			sig = new ObjectSignature(serviceType);
		}
		if (returnPath != null)
			sig.setReturnPath(returnPath);
		return sig;
	}

	public static EvaluationSignature sig(Evaluator evaluator,
										  ReturnPath returnPath) throws SignatureException {
		EvaluationSignature sig = null;
		if (evaluator instanceof Scopable) {
			sig = new EvaluationSignature(new Par((Identifiable) evaluator));
		} else {
			sig = new EvaluationSignature(evaluator);
		}
		sig.setReturnPath(returnPath);
		return sig;
	}

	public static EvaluationSignature sig(Evaluator evaluator) throws SignatureException {
		return new EvaluationSignature(evaluator);
	}

	public static Signature sig(Exertion exertion, String componentExertionName) {
		Exertion component = (Exertion) exertion.getMogram(componentExertionName);
		return component.getProcessSignature();
	}

	public static Signature sig(Path source) {
		return new NetletSignature(source);
	}

	public static EvaluationTask task(EvaluationSignature signature)
			throws ExertionException {
		return new EvaluationTask(signature);
	}

	public static EvaluationTask task(Evaluation evaluator) throws ExertionException, SignatureException {
		try {
			return new EvaluationTask(evaluator);
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	public static Signature builder(Signature signature) {
		signature.setType(Type.BUILDER);
		return signature;
	}

	public static Signature pre(Signature signature) {
		signature.setType(Type.PRE);
		return signature;
	}

	public static Signature post(Signature signature) {
		signature.setType(Type.POST);
		return signature;
	}

	public static Signature pro(Signature signature) {
		signature.setType(Type.PROC);
		return signature;
	}

	public static Signature apd(Signature signature) {
		signature.setType(Type.APD_DATA);
		return signature;
	}

	public static Signature type(Signature signature, Signature.Type type) {
		signature.setType(type);
		return signature;
	}

	public static EvaluationTask task(EvaluationSignature signature,
									  Context context) throws ExertionException {
		return new EvaluationTask(signature, context);
	}

	public static Fidelity fiFi(String name) {
		return new Fidelity(name);
	}


//	public static Tuple2<String, String> cFi(String componentPath, String fidelityName) {
//		return new Tuple2<String, String> (componentPath, fidelityName);
//	}

	public static Fidelity<Arg> cFi(String componentPath, String fidelityName) {
		Fidelity<Arg> fi = new Fidelity(componentPath, new Name(fidelityName));
		fi.setPath(componentPath);
		fi.type = Fidelity.Type.COMPONENT;
		return fi;
	}

	public static Fidelity<Arg> fi(String name, String... selectors) {
		Fidelity fi = new Fidelity(name, selectors);
		fi.type = Fidelity.Type.NAME;
		return fi;
	}

	public static Fidelity<Arg> fi(String name, Arg... selectors) {
		Fidelity fi = new Fidelity(name, selectors);
		fi.type = Fidelity.Type.NAME;
		return fi;
	}

	public static Map<String, Fidelity> sFis(Mogram exertion) {
		return ((ServiceExertion) exertion).getServiceFidelities();
	}

	public static Fidelity<Signature> sFi(Mogram mogram) {
		return mogram.getFidelity();
	}

	public static String fiName(Mogram exertion) {
		return ((ServiceExertion) exertion).getSelectedFidelitySelector();
	}

	public static Map<String, Fidelity> srvFis(Exertion exertion) {
		return exertion.getFidelities();
	}

	public static Fidelity<Fidelity> fi(String name, Fidelity... fidelities) {
		Fidelity<Fidelity> fi = new Fidelity(new Fidelity(fidelities));
		fi.setName(name);
		return fi;
	}

	public static Fidelity<Fidelity> fi(Fidelity... fidelities) {
		Fidelity<Fidelity> fi = new Fidelity(new Fidelity(fidelities));
		return fi;
	}

	public static MultiFidelity<Entry> mFi(Entry... entries) {
		MultiFidelity<Entry> multiFi = new MultiFidelity(new Fidelity(entries));
		return multiFi;
	}

	public static MultiFidelity<Signature> mFi(Signature... signatures) {
		MultiFidelity<Signature> multiFi = new MultiFidelity(new Fidelity(signatures));
		return multiFi;
	}

	public static MultiFidelity<Signature> mFi(Morpher morpher, Signature... signatures) {
		MultiFidelity<Signature> multiFi = new MultiFidelity(new Fidelity(signatures));
		multiFi.setMorpher(morpher);
		return multiFi;
	}

	public static MultiFidelity<Signature> multiFi(Signature... signatures) {
		MultiFidelity<Signature> multiFi = new MultiFidelity(new Fidelity(signatures));
		return multiFi;
	}

	public static Fidelity<Signature> sFi(Signature... signatures) {
		Fidelity<Signature> fi = new Fidelity(signatures);
		fi.type = Fidelity.Type.EXERT;
		return fi;
	}

	public static Fidelity<Entry> eFi(Entry... entries) {
		Fidelity<Entry> fi = new Fidelity(entries);
		fi.type = Fidelity.Type.ENTRY;
		return fi;
	}

	public static Fidelity<?> fi(String name) {
		Fidelity<?> fi = new Fidelity(name);
		fi.type = Fidelity.Type.EMPTY;
		return fi;
	}

	public static Fidelity<?> fi(String path, String name) {
		Fidelity<?> fi = new Fidelity(name);
		fi.setPath(path);
		fi.type = Fidelity.Type.EMPTY;
		return fi;
	}

	public static Fidelity<Fidelity> sFi(Fidelity... fidelities) {
		Fidelity<Fidelity> fi = new Fidelity<Fidelity>(fidelities);
		fi.type = Fidelity.Type.MULTI;
		return fi;
	}

	public static Fidelity<Fidelity> sFi(String name, Fidelity... fidelities) {
		Fidelity<Fidelity> fi = new Fidelity<Fidelity>(name, fidelities);
		fi.type = Fidelity.Type.COMPOSITE;
		return fi;
	}

	public static Fidelity<Signature> sFi(String name, Signature... signatures) {
		Fidelity<Signature> fi = new Fidelity<Signature>(name, signatures);
		fi.type = Fidelity.Type.EXERT;
		return fi;
	}

	public static ObjectSignature sig(String operation, Object object)
			throws SignatureException {
		return sig(operation, object, null, null, null);
	}

	public static ObjectSignature sig(String operation, Object object,
									  Class[] types, Object... args) throws SignatureException {
		if (args == null || args.length == 0)
			return sig(operation, object, (String) null, types);
		else
			return sig(operation, object, null, types, args);
	}

	public static ObjectSignature sig(String operation, Object object, String initOperation,
									  Class[] types) throws SignatureException {
		try {
			if (object instanceof Class && ((Class) object).isInterface()) {
				if (initOperation != null)
					return new NetSignature(operation, (Class) object, Sorcer.getActualName(initOperation));
				else
					return new NetSignature(operation, (Class) object);
			} else if (object instanceof Class) {
				return new ObjectSignature(operation, object, initOperation,
						types == null || types.length == 0 ? null : types);
			} else {
				return new ObjectSignature(operation, object,
						types == null || types.length == 0 ? null : types);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new SignatureException(e);
		}
	}

	public static ObjectSignature sig(Object object, String initSelector,
									  Class[] types, Object[] args) throws SignatureException {
		try {
			return new ObjectSignature(object, initSelector, types, args);
		} catch (Exception e) {
			e.printStackTrace();
			throw new SignatureException(e);
		}
	}

	public static ObjectSignature sig(String selector, Object object, String initSelector,
									  Class[] types, Object[] args) throws SignatureException {
		try {
			return new ObjectSignature(selector, object, initSelector, types, args);
		} catch (Exception e) {
			e.printStackTrace();
			throw new SignatureException(e);
		}
	}

	public static ObjectTask task(ObjectSignature signature)
			throws SignatureException {
		return new ObjectTask(signature.getSelector(),
				(ObjectSignature) signature);
	}

	public static ObjectTask task(ObjectSignature signature, Context context)
			throws SignatureException {
		return new ObjectTask(signature.getSelector(), signature, context);
	}

	public static Task task(String name, Signature signature, Context context)
			throws SignatureException {
		Task task = task(signature, context);
		task.setName(name);
		return task;
	}

	public static Task task(Signature signature) throws SignatureException {
		return task(signature, null);
	}

	public static ModelingTask modelerTask(String name, Signature signature)
			throws SignatureException {
		ModelingTask task = null;
		if (signature instanceof NetSignature) {
			task = new ModelerNetTask(name, signature);
		} else if (signature instanceof ObjectSignature) {
			task = new ModelerObjectTask(name, signature);
		}
		return task;
	}

	public static ModelingTask modelerTask(Signature signature, Context context)
			throws SignatureException {
		ModelingTask task = null;
		if (signature instanceof NetSignature) {
			task = new ModelerNetTask(signature, context);
		} else if (signature instanceof ObjectSignature) {
			task = new ModelerObjectTask(signature, context);
		}
		return task;
	}

	public static Task task(Signature signature, Context context)
			throws SignatureException {
		Task task;
		if (signature instanceof NetSignature) {
			task = new NetTask(signature, context);
		} else if (signature instanceof ObjectSignature) {
			task = new ObjectTask(signature, context);
		} else if (signature instanceof EvaluationSignature) {
			task = new EvaluationTask((EvaluationSignature) signature,
					context);
		} else {
			task = new Task(signature, context);
		}
		if (((ServiceSignature) signature).isProvisionable())
			task.setProvisionable(true);
		return task;
	}

	public static Task batch(String name, Object... elems)
			throws ExertionException {
		Task batch = task(name, elems);
		if (batch.getFidelity().getSelects().size() > 1)
			return batch;
		else
			throw new ExertionException(
					"A batch should comprise of more than one signature.");
	}

	public static Task task(String name, Object... elems)
			throws ExertionException {
		Context context = null;
		List<Signature> ops = new ArrayList<Signature>();
		String tname;
		if (name == null || name.length() == 0)
			tname = getUnknown();
		else
			tname = name;
		Task task = null;
		Access access = null;
		Flow flow = null;
		List<Fidelity> fidelities = null;
		ControlContext cc = null;
		for (Object o : elems) {
			if (o instanceof ControlContext) {
				cc = (ControlContext) o;
			} else if (o instanceof Context) {
				context = (Context) o;
			} else if (o instanceof Signature) {
				ops.add((Signature) o);
			} else if (o instanceof String) {
				tname = (String) o;
			} else if (o instanceof Access) {
				access = (Access) o;
			} else if (o instanceof Flow) {
				flow = (Flow) o;
			} else if (o instanceof Fidelity) {
				if (fidelities == null)
					fidelities = new ArrayList<Fidelity>();
				fidelities.add((Fidelity) o);
			}
		}
		Signature ss = null;
		if (ops.size() == 1) {
			ss = ops.get(0);
		} else if (ops.size() > 1) {
			for (Signature s : ops) {
				if (s.getType() == Signature.SRV) {
					ss = s;
					break;
				}
			}
		}
		if (ss != null) {
			try {
				if (((ServiceSignature)ss).isModelerSignature()) {
					task = (Task) modelerTask(tname, ss);
				} else if (ss instanceof NetSignature) {
					task = new NetTask(tname, ss);
				} else if (ss instanceof ObjectSignature) {
					task = new ObjectTask(ss.getSelector(), (ObjectSignature) ss);
					task.setName(tname);
				} else if (ss instanceof EvaluationSignature) {
					task = new EvaluationTask(tname, (EvaluationSignature) ss);
				} else if (ss instanceof ServiceSignature) {
					task = new Task(tname, ss);
				}
			} catch (SignatureException e) {
				throw new ExertionException(e);
			}
			ops.remove(ss);
		}
		if (fidelities != null && fidelities.size() > 0) {
			task = new Task(tname);
			for (int i = 0; i < fidelities.size(); i++) {
				task.addFidelity(fidelities.get(i));
			}
			task.setFidelity(fidelities.get(0));
			task.setSelectedFidelitySelector(fidelities.get(0).getName());
		} else {
			for (Signature signature : ops) {
				task.addSignature(signature);
			}
		}

		if (context == null) {
			context = new PositionalContext();
		}
		task.setContext(context);

		if (access != null) {
			task.setAccess(access);
		}
		if (flow != null) {
			task.setFlow(flow);
		}
		if (cc != null) {
			task.updateStrategy(cc);
		}
		if (ss != null && ((ServiceSignature) ss).isProvisionable()) {
			task.setProvisionable(true);
		}
		return task;
	}

	public static <M extends Mogram> M mog(Object... items) throws MogramException {
		return mogram(items);
	}

	public static <M extends Model> M model(Object... items) throws ContextException, SortingException {
		String name = "unknown" + count++;
		boolean hasEntry = false;
		boolean evalType = false;
		boolean parType = false;
		boolean srvType = false;
		boolean hasExertion = false;
		boolean hasSignature = false;
		boolean manualDeps = false;
		for (Object i : items) {
			if (i instanceof String) {
				name = (String) i;
			} else if (i instanceof Exertion) {
				hasExertion = true;
			} else if (i instanceof Signature) {
				hasSignature = true;
			} else if (i instanceof Entry) {
				try {
					hasEntry = true;
					if (i instanceof Par)
						parType = true;
					else if (i instanceof Srv) {
						srvType = true;
					} else if (((Entry) i).asis() instanceof Evaluation) {
						evalType = true;
					}
				} catch (Exception e) {
					throw new ModelException(e);
				}
			} else if (i.equals(Strategy.Flow.EXPLICIT)) {
				manualDeps = true;
			}
		}
		if ((hasEntry || hasSignature && hasEntry) && !hasExertion) {
			Model mo = null;
			if (srvType)
				mo = srvModel(items);
			else if (parType)
				try {
					return (M) parModel(name, items);
				} catch (Exception e) {
					throw new ModelException(e);
				}
			else if (evalType)
				mo = entModel(items);
			else
				mo = context(items);

			mo.setName(name);
			if (mo instanceof SrvModel && ! manualDeps)
				mo = new SrvModelAutoDeps((SrvModel)mo).get();
			return (M) mo;
		}
		throw new ModelException("do not know what model to create");
	}


	public static <M extends Mogram> M mogram(Object... items) throws MogramException {
		String name = "unknown" + count++;
		if (items.length == 1 && items[0] instanceof NetletSignature) {
			String source = ((NetletSignature)items[0]).getServiceSource();
			if(source != null) {
				try {
					ScriptExerter se = new ScriptExerter(System.out, null, Sorcer.getWebsterUrl(), true);
					se.readFile(new File(source));
					return (M)se.parse();
				} catch (Throwable e) {
					throw new MogramException(e);
				}
			}
		}
		boolean hasEntry = false;
		boolean hasExertion = false;
		boolean hasContext = false;
		boolean hasSignature = false;
		for (Object i : items) {
			if (i instanceof String) {
				name = (String) i;
			} else if (i instanceof Exertion) {
				hasExertion = true;
			} else if (i instanceof Context) {
				hasContext = true;
			} else if (i instanceof Signature) {
				hasSignature = true;
			} else if (i instanceof Entry) {
				hasEntry = true;
			}
		}
		try {
			if ((hasSignature && hasContext || hasExertion) && !hasEntry) {
				return (M) exertion(name, items);
			} else {
				return model(items);
			}
		} catch(Exception e) {
			throw new MogramException("do not know what mogram to create");

		}
	}

	public static <E extends Exertion> E xrt(String name, Object... elems)
			throws ExertionException, ContextException, SignatureException {
		return (E) exertion(name, elems);
	}

	public static <E extends Exertion> E exertion(String name, Object... items) throws ExertionException,
			ContextException, SignatureException {
		List<Mogram> exertions = new ArrayList<Mogram>();
		Signature sig = null;
		Context cxt = null;
		boolean isBlock =false;
		for (int i = 0; i < items.length; i++) {
			if (items[i] instanceof Exertion || items[i] instanceof EntModel ) {
				exertions.add((Mogram) items[i]);
				if (items[i] instanceof ConditionalMogram)
					isBlock = true;
			} else if (items[i] instanceof Signature) {
				sig = (Signature) items[i];
			} else if (items[i] instanceof String) {
				name = (String) items[i];
			}
		}
		if (isBlock || exertions.size() > 0 && sig != null
				&& (sig.getServiceType() == Concatenator.class
				|| sig.getServiceType() == ServiceConcatenator.class)) {
			return (E) block(items);
		} else if (exertions.size() > 1) {
			Job j = job(items);
			j.setName(name);
			return (E) j;
		} else {
			return (E)task(name, items);
		}
	}

	public static Job job(Object... elems) throws ExertionException,
			ContextException, SignatureException {
		String name = "job-" + count++;
		Signature signature = null;
		ControlContext control = null;
		Context<?> data = null;
		ReturnPath rp = null;
		List<Exertion> exertions = new ArrayList<Exertion>();
		List<Pipe> pipes = new ArrayList<Pipe>();
		List<Fidelity> fidelities = null;
		List<Fidelity> fiList = null;
		List<MapContext> connList = new ArrayList<MapContext>();

		for (int i = 0; i < elems.length; i++) {
			if (elems[i] instanceof String) {
				name = (String) elems[i];
			} else if (elems[i] instanceof Exertion) {
				exertions.add((Exertion) elems[i]);
			} else if (elems[i] instanceof ControlContext) {
				control = (ControlContext) elems[i];
			} else if (elems[i] instanceof MapContext) {
				connList.add(((MapContext)elems[i]));
			} else if (elems[i] instanceof Context) {
				data = (Context<?>) elems[i];
			} else if (elems[i] instanceof Pipe) {
				pipes.add((Pipe) elems[i]);
			} else if (elems[i] instanceof Signature) {
				signature = ((Signature) elems[i]);
			} else if (elems[i] instanceof ReturnPath) {
				rp = ((ReturnPath) elems[i]);
			} else if (elems[i] instanceof Fidelity) {
				if (fidelities == null)
					fidelities = new ArrayList<Fidelity>();
				fidelities.add((Fidelity) elems[i]);
			} else if (elems[i] instanceof FidelityContext) {
				if (fiList == null)
					fiList = new ArrayList<Fidelity>();
				fiList.add((Fidelity) elems[i]);
			}

		}
		Job job = null;
		boolean defaultSig = false;
		if (signature == null && fidelities == null) {
			signature = sig("exert", Jobber.class);
			defaultSig = true;
		}
		if (signature instanceof NetSignature) {
			job = new NetJob(name);
		} else if (signature instanceof ObjectSignature) {
			job = new ObjectJob(name);
		}
		if (fidelities == null) {
			if (!defaultSig) {
				job.getFidelity().getSelects().clear();
				job.addSignature(signature);
			}
		} else {
			job = new Job(name);
			for (int i = 0; i < fidelities.size(); i++) {
				job.addFidelity(fidelities.get(i));
			}
			job.setFidelity(fidelities.get(0));
			job.setSelectedFidelitySelector(fidelities.get(0).getName());
		}

		if (data != null)
			job.setContext(data);

		if (rp != null) {
			((ServiceContext) job.getDataContext()).setReturnPath(rp);
		}

		if (control != null)
			job.setControlContext(control);

		if (job instanceof NetJob && control != null) {
			job.setControlContext(control);
			if (control.getAccessType().equals(Access.PULL)) {
				Signature procSig = job.getProcessSignature();
				procSig.setServiceType(Spacer.class);
				job.getFidelity().getSelects().clear();
				job.addSignature(procSig);
				job.getDataContext().setExertion(job);
			}
		}
		if (fiList != null) {
			for (Fidelity fi : fiList) {
				job.addFidelity(fi);
			}
		}
		if (connList != null) {
			for (MapContext conn : connList) {
				if (conn.direction == MapContext.Direction.IN)
					((ServiceContext)job.getDataContext()).getMogramStrategy().setInConnector(conn);
				else
					((ServiceContext)job.getDataContext()).getMogramStrategy().setOutConnector(conn);
			}
		}

		if (exertions.size() > 0) {
			for (Exertion ex : exertions) {
				job.addMogram(ex);
			}
			for (Pipe p : pipes) {
//				logger.debug("from context: "
//						+ ((Exertion) p.in).getDataContext().getName()
//						+ " path: " + p.inPath);
//				logger.debug("to context: "
//						+ ((Exertion) p.out).getDataContext().getName()
//						+ " path: " + p.outPath);
				// find component exertions for thir paths
				if (!p.isExertional()) {
					p.out = (Exertion)job.getComponentMogram(p.outComponentPath);
					p.in = (Exertion)job.getComponentMogram(p.inComponentPath);
				}
				((Exertion) p.out).getDataContext().connect(p.outPath,
						p.inPath, ((Exertion) p.in).getContext());
			}
		} else
			throw new ExertionException("No component exertion defined.");

		return job;
	}

	public static Object get(Context context) throws ContextException,
			RemoteException {
		return context.getReturnValue();
	}

	public static Object get(Context context, int index)
			throws ContextException {
		if (context instanceof PositionalContext)
			return ((PositionalContext) context).getValueAt(index);
		else
			throw new ContextException("Not PositionalContext, index: " + index);
	}

	public static Object get(Exertion exertion) throws ContextException,
			RemoteException {
		return exertion.getContext().getReturnValue();
	}

	public static <T extends Evaluation> Object asis(T evaluation) throws EvaluationException {
		if (evaluation instanceof Evaluation) {
			try {
				synchronized (evaluation) {
					return evaluation.asis();
				}
			} catch (RemoteException e) {
				throw new EvaluationException(e);
			}
		} else {
			throw new EvaluationException(
					"asis value can only be determined for objects of the "
							+ Evaluation.class + " type");
		}
	}

	public static <V> V take(Variability<V> variability)
			throws EvaluationException {
		try {
			synchronized (variability) {
				variability.valueChanged(null);
				V val = variability.getValue();
				variability.valueChanged(null);
				return val;
			}
		} catch (Exception e) {
			throw new EvaluationException(e);
		}
	}

	public static Object get(Service mogram, String path)
			throws ContextException {
		Object obj = null;
		if (mogram instanceof Context) {
			obj = ((ServiceContext)mogram).get(path);
			if (mogram instanceof EntModel) {
				while (obj instanceof Entry && ((Entry)obj).getName().equals(path)) {
					obj = ((Entry)obj).value();
				}
			}
//			if (obj != null) {
//				while (obj instanceof Mappable ||
//						(obj instanceof Reactive && ((Reactive) obj).isReactive())) {
//					try {
//						obj = ((Evaluation) obj).asis();
//					} catch (RemoteException e) {
//						throw new ContextException(e);
//					}
//				}
//			}
		} else if (mogram instanceof Exertion) {
			obj = ((Exertion)mogram).getValue(path);
		} else if (mogram instanceof Model) {
			return rasis((ServiceContext) mogram, path);
		} else if (mogram instanceof Exertion) {
			obj = (((Exertion) mogram).getContext()).getValue(path);
		}
		return obj;
	}

//	public static Object get(Mogram service, String path)
//			throws ContextException, ExertionException {
//		Object obj = ((ServiceContext) service).asis(path);
//		if (obj != null) {
//			while (obj instanceof Mappable ||
//					(obj instanceof Reactive && ((Reactive)obj).isReactive())) {
//				try {
//					obj = ((Evaluation) obj).asis();
//				} catch (RemoteException e) {
//					throw new ContextException(e);
//				}
//			}
//		} else if (service instanceof Exertion) {
//			obj = ((Exertion)service).getValue(path);
//		} else if (service instanceof Context) {
//			obj = ((Context)service).getValue(path);
//		}
//		return obj;
//	}

	public static Object get(Exertion exertion, String component, String path)
			throws ExertionException {
		Exertion c = (Exertion) exertion.getMogram(component);
		try {
			return get((Mogram) c, path);
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	public static <T> T softValue(Context<T> context, String path) throws ContextException {
		return context.getSoftValue(path);
	}

	public static <K, V> V keyValue(Map<K, V> map, K path) throws ContextException {
		return map.get(path);
	}

	public static <K, V> V pathValue(Map<K, V> map, K path) throws ContextException {
		return map.get(path);
	}

	public static <V> V pathValue(Mappable<V> map, String path, Arg... args) throws ContextException {
		return map.getValue(path, args);
	}

	public static Object content(URL url) throws EvaluationException {
		if (url instanceof URL) {
			try {
				return ((URL) url).getContent();
			} catch (Exception e) {
				throw new EvaluationException(e);
			}
		} else {
			throw new EvaluationException("Expected URL for its content");
		}
	}

	public static <T extends Mogram> T exert(Exerter service, T mogram, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		return service.exert(mogram, null, entries);
	}

	public static <T extends Mogram> T exert(Mogram service, T mogram, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		return service.exert(mogram, null, entries);
	}

	public static <T extends Mogram> T exert(Mogram service, T mogram, Transaction txn, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		return service.exert(mogram, txn, entries);
	}

	public static <T> T eval(Context<T> model, Arg... args)
			throws ContextException {
		return value(model, args);
	}

	public static <T> T value(Context<T> model, Arg... args)
			throws ContextException {
		try {
			synchronized (model) {
				if (model instanceof ParModel) {
					return ((ParModel<T>) model).getValue(args);
				} else {
					return (T) ((ServiceContext)model).getValue(args);
				}
			}
		} catch (Exception e) {
			throw new ContextException(e);
		}
	}

	public static <T> T eval(Evaluation<T> evaluation, Arg... args)
			throws EvaluationException {
		return value(evaluation, args);
	}

	public static <T> T value(Evaluation<T> evaluation, Arg... args)
			throws EvaluationException {
		try {
			synchronized (evaluation) {
				if (evaluation instanceof Exertion) {
					return (T) evaluate((Exertion) evaluation, args);
				} else if (evaluation instanceof Entry){
					return evaluation.getValue(args);
				} else if (evaluation instanceof Incrementor){
					return ((Incrementor<T>) evaluation).next();
				} else {
					return (T) ((Evaluation)evaluation).getValue(args);
				}
			}
		} catch (Exception e) {
			throw new EvaluationException(e);
		}
	}

	public static Object eval(Model model, String evalSelector,
							  Arg... args) throws ContextException {
		return value((Context<Object>) model, evalSelector, args);
	}

	public static Object value(Model model, String evalSelector,
							   Arg... args) throws ContextException {
		return value((Context<Object>) model, evalSelector, args);
	}

	public static Object exec(Service service, Arg... args) throws MogramException, RemoteException {
		try {
			if (service instanceof Entry || service instanceof Signature ) {
				return service.exec(args);
			} else if (service instanceof Mogram) {
				return new sorcer.core.provider.exerter.ServiceShell().exec(service, args);
			} else {
				return service.exec(args);
			}

		} catch (Exception e) {
			throw new MogramException(e);
		}
	}

	public static <T> T eval(Context<T> model, String path,
							 Arg... args) throws ContextException {
		return value(model, path, args);
	}

	public static <T> T val(Context<T> model, String path,
							Arg... entries) throws ContextException {
		return value(model, path, entries);
	}

	public static <T> T v(Context<T> model, String path,
						  Arg... args) throws ContextException {
		return value(model, path, args);
	}

	public static <T> T value(Context<T> model, String path,
							  Arg... args) throws ContextException {
		if (model instanceof ParModel) {
			return (T) ((ParModel) model).getValue(path,
					args);
		}  else if (model instanceof Context) {
			try {
				Object val = ((Context) model).getValue(path,
						args);
				if (SdbUtil.isSosURL(val)) {
					return (T) ((URL) val).getContent();
				} else {
					return (T)val;
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw new ContextException(e);
			}
		}
		return null;
	}

	public static <T> T eval(Evaluation<T> evaluation, String evalSelector,
							 Arg... args) throws EvaluationException {
		return value(evaluation, evalSelector, args);
	}

	public static <T> T value(Evaluation<T> evaluation, String evalSelector,
							  Arg... args) throws EvaluationException {
		if (evaluation instanceof Exertion) {
			try {
				((ServiceContext)((Exertion) evaluation).getDataContext())
						.setReturnPath(new ReturnPath(evalSelector));
				return (T) evaluate((Exertion) evaluation, args);
			} catch (Exception e) {
				e.printStackTrace();
				throw new EvaluationException(e);
			}
		}
		return null;
	}

	/**
	 * Assigns the maker for this context, for example "triplet|one|two|three" is a
	 * marker (relation) named 'triplet' as a product of three "places" path, info, _3.
	 *
	 * @param context
	 * @param marker
	 * @throws ContextException
	 */
	public static void marker(Context context, String marker)
			throws ContextException {
		context.setAttribute(marker);
	}

	/**
	 * Associates a given path in this context with a tuple of a marker
	 * defined for this context. If a marker, for example, is
	 * "triplet|one|two|three" then its tuple can be "triplet|mike|w|sobol" where
	 * 'triplet' is the name of relation and its proper tuple is 'mike|w|sobol'.
	 *
	 * @param context
	 * @param path
	 * @param tuple
	 * @return
	 * @throws ContextException
	 */
	public static Context mark(Context context, String path, String tuple)
			throws ContextException {
		return context.mark(path, tuple);
	}

	public static <T> List<T> valuesAt(Context<T> context, String tuple) throws ContextException {
		return context.getMarkedValues(tuple);
	}

	public static <T> T getAt(Context<T> context, String tuple) throws ContextException {
		return valuesAt(context, tuple).get(0);
	}

	public static <T> T getAt(Context<T> context, int i) throws ContextException {
		if (!(context instanceof Positioning))
			throw new ContextException("Not positional Context: " + context.getName());
		return context.getMarkedValues("i|" + i).get(0);
	}

	public static <T> List<T> select(Context<T> context, int... positions) throws ContextException {
		List<T> values = new ArrayList<T>(positions.length);
		for (int i : positions) {
			values.add(getAt(context, i));
		}
		return values;
	}

	public static List<Mogram> exertions(Mogram mogram) {
		if (mogram instanceof Exertion)
			return ((Exertion)mogram).getAllMograms();
		else
			return null;
	}

	public static Mogram exertion(Exertion xrt, String componentExertionName) {
		return xrt.getComponentMogram(componentExertionName);
	}

	public static Mogram tracable(Mogram xrt) {
		List<Mogram> mograms = ((ServiceMogram) xrt).getAllMograms();
		for (Mogram m : mograms) {
			((Exertion) m).getControlContext().setTracable(true);
		}
		return xrt;
	}

	public static List<String> trace(Mogram xrt) {
		List<Mogram> mograms = ((ServiceMogram)xrt).getAllMograms();
		List<String> trace = new ArrayList<String>();
		for (Mogram m : mograms) {
			trace.addAll(((Exertion) m).getControlContext().getTrace());
		}
		return trace;
	}

	public static void print(Object obj) {
		System.out.println(obj.toString());
	}

	public static Object evaluate(Mogram mogram, Arg... args)
			throws ContextException, RemoteException, ExertionException {
		synchronized (mogram) {
			if (mogram instanceof Exertion)
				return new ServiceShell().evaluate(mogram, args);
			else if (mogram instanceof Modeling) {
				mogram.substitute(args);
				((Modeling) mogram).evaluate();
				return ((Model)mogram).getResult();
			} else {
				return ((Evaluation) mogram).getValue(args);
			}
		}
	}


	public static List<ThrowableTrace> exceptions(Exertion exertion) throws RemoteException {
		return exertion.getExceptions();
	}

	public static <T extends Mogram> T exert(T mogram, Arg... args) throws MogramException {
		try {
			return mogram.exert(null, args);
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	public static <T extends Mogram> T exert(T input,
											 Transaction transaction,
											 Arg... entries) throws ExertionException {
		return new sorcer.core.provider.exerter.ServiceShell().exert(input, transaction, entries);
	}

	public static OutputEntry output(Object value) {
		return new OutputEntry(null, value, 0);
	}

	public static ReturnPath result(String path) {
		return new ReturnPath(path);
	}

	public static ReturnPath result(Out paths) {
		return new ReturnPath(null, paths);
	}

	public static ReturnPath self() {
		return new ReturnPath();
	}

	public static ReturnPath result(String path, Out outPaths) {
		return new ReturnPath(path, outPaths);
	}

	public static ReturnPath result(String path, In inPaths) {
		return new ReturnPath(path, inPaths);
	}

	public static ReturnPath result(In inPaths) {
		return new ReturnPath(Signature.SELF, inPaths);
	}

	public static ReturnPath result(String path, In inPaths, Out outPaths) {
		return new ReturnPath(path, inPaths, outPaths);
	}

	public static ReturnPath result(String path, Direction direction) {
		return new ReturnPath(path, direction);
	}

	public static ReturnPath result(String path, Direction direction,
									Path[] paths) {
		return new ReturnPath(path, direction, paths);
	}

	public static ReturnPath result(String path, Class type, Path[] paths) {
		return new ReturnPath(path, Direction.OUT, type, paths);
	}

	protected static String getUnknown() {
		return "unknown" + count++;
	}


	public static class Range extends Tuple2<Integer, Integer> {
		private static final long serialVersionUID = 1L;
		public Integer[] range;

		public Range(Integer from, Integer to) {
			this._1 = from;
			this._2 = to;
		}

		public Range(Integer[] range) {
			this.range = range;
		}

		public Integer[] range() {
			return range;
		}

		public int from() {
			return _1;
		}

		public int to() {
			return _2;
		}

		public String toString() {
			if (range != null)
				return Arrays.toString(range);
			else
				return "[" + _1 + "-" + _2 + "]";
		}
	}

	// putLink(String name, String path, Context linkedContext, String offset)
	public static Object link(Context context, String path,
							  Context linkedContext, String offset) throws ContextException {
		context.putLink(null, path, linkedContext, offset);
		return context;
	}

	public static Context link(Context context, String path,
							   Context linkedContext) throws ContextException {
		context.putLink(null, path, linkedContext, "");
		return context;
	}

	public static Link getLink(Context context, String path) throws ContextException {
		return context.getLink(path);
	}

	public static <T> ControlContext strategy(T... entries) {
		ControlContext cc = new ControlContext();
		List<Signature> sl = new ArrayList<Signature>();
		for (Object o : entries) {
			if (o instanceof Access) {
				cc.setAccessType((Access) o);
			} else if (o instanceof Flow) {
				cc.setFlowType((Flow) o);
			} else if (o instanceof Monitor) {
				cc.isMonitorable((Monitor) o);
			} else if (o instanceof Provision) {
				if (o.equals(Provision.YES))
					cc.setProvisionable(true);
				else
					cc.setProvisionable(false);
			} else if (o instanceof Strategy.Shell) {
				if (o.equals(Strategy.Shell.REMOTE))
					cc.setShellRemote(true);
				else
					cc.setShellRemote(false);
			} else if (o instanceof Wait) {
				cc.isWait((Wait) o);
			} else if (o instanceof Signature) {
				sl.add((Signature) o);
			} else if (o instanceof Opti) {
				cc.setOpti((Opti) o);
			} else if (o instanceof Exec.State) {
				cc.setExecState((Exec.State) o);
			}
		}
		cc.setSignatures(sl);
		return cc;
	}

	public static Flow flow(Entry entry) throws EvaluationException {
		return ((Strategy)value(entry)).getFlowType();
	}

	public static Access access(Entry entry) throws EvaluationException {
		return ((Strategy)value(entry)).getAccessType();
	}

	public static Flow flow(Strategy strategy) {
		return strategy.getFlowType();
	}

	public static Access access(Strategy strategy) {
		return strategy.getAccessType();
	}

	public static EntryList inputs(Entry...  entries) {
		return initialDesign(entries);
	}

	public static EntryList initialDesign(Entry...  entries) {
		EntryList el = new EntryList(entries);
		el.setType(EntryList.Type.INITIAL_DESIGN);
		return el;
	}

	public static PathResponse target(Object object) {
		return new PathResponse(object);
	}

	public static class PathResponse extends Path {
		private static final long serialVersionUID = 1L;
		public Object target;

		public PathResponse(Object target) {
			this.target = target;
		}

		public PathResponse(String path, Object target) {
			this.target = target;
			this.path = path;
		}

		@Override
		public String toString() {
			return "target: " + target;
		}
	}

	public static class result extends Tuple2 {

		private static final long serialVersionUID = 1L;

		Class returnType;

		result(String path) {
			this._1 = path;
		}

		result(String path, Class returnType) {
			this._1 = path;
			this._2 = returnType;
		}

		public Class returnPath() {
			return (Class) this._2;
		}

		@Override
		public String toString() {
			return "return path: " + _1;
		}
	}

	public static ParameterTypes parameterTypes(Class... parameterTypes) {
		return new ParameterTypes(parameterTypes);
	}

	public static class ParameterTypes extends Path {
		private static final long serialVersionUID = 1L;
		public Class[] parameterTypes;

		public ParameterTypes(Class... parameterTypes) {
			this.parameterTypes = parameterTypes;
		}

		public ParameterTypes(String path, Class... parameterTypes) {
			this.parameterTypes = parameterTypes;
			this.path = path;
		}

		@Override
		public String toString() {
			return "parameterTypes: " + Arrays.toString(parameterTypes);
		}
	}

	public static Args parameterValues(Object... args) {
		return new Args(args);
	}

	public static Args args(Object... args) {
		return new Args(args);
	}

	public static class Args extends Path {
		private static final long serialVersionUID = 1L;

		public Object[] args;

		public Args(Object... args) {
			this.args = args;
		}

		public Args(String path, Object... args) {
			this.args = args;
			this.path = path;
		}

		public Arg[] args() {
			Arg[] as = new Arg[args.length];
			for (int i = 0; i < args.length; i++) {
				as[i] = new Entry(args[i].toString());
			}
			return as;
		}

		public String[] argsToStrings() {
			String[] as = new String[args.length];
			for (int i = 0; i < args.length; i++) {
				as[i] = args[i].toString();
			}
			return as;
		}

		@Override
		public String toString() {
			return "args: " + Arrays.toString(args);
		}
	}

	public static class Pipe {
		String inPath;
		String outPath;
		Mappable in;
		Mappable out;
		String outComponentPath;
		String inComponentPath;

		Par parEntry;

		Pipe(Exertion out, String outPath, Mappable in, String inPath) {
			this.out = out;
			this.outPath = outPath;
			this.in = in;
			this.inPath = inPath;
			if ((in instanceof Exertion) && (out instanceof Exertion)) {
				try {
					parEntry = new Par(outPath, inPath, in);
				} catch (ContextException e) {
					e.printStackTrace();
				}
				((ServiceExertion) out).addPersister(parEntry);
			}
		}

		Pipe(OutEndPoint outEndPoint, InEndPoint inEndPoint) {
			this.out = outEndPoint.out;
			this.outPath = outEndPoint.outPath;
			this.outComponentPath = outEndPoint.outComponentPath;
			this.in = inEndPoint.in;
			this.inPath = inEndPoint.inPath;
			this.inComponentPath = inEndPoint.inComponentPath;

			if ((in instanceof Exertion) && (out instanceof Exertion)) {
				try {
					parEntry = new Par(outPath, inPath, in);
				} catch (ContextException e) {
					e.printStackTrace();
				}
				((ServiceExertion) out).addPersister(parEntry);
			}
		}

		public boolean isExertional() {
			return in != null && out != null;
		}
	}

	public static Par persistent(Pipe pipe) {
		pipe.parEntry.setPersistent(true);
		return pipe.parEntry;
	}

	private static class InEndPoint {
		String inPath;
		Mappable in;
		String inComponentPath;

		InEndPoint(Mappable in, String inDataPath) {
			this.inPath = inDataPath;
			this.in = in;
		}

		InEndPoint(String inComponentPath, String inDataPath) {
			this.inPath = inDataPath;
			this.inComponentPath = inComponentPath;
		}
	}

	private static class OutEndPoint {
		public String outPath;
		public Mappable out;
		public String outComponentPath;

		OutEndPoint(Mappable out, String outDataPath) {
			this.outPath = outDataPath;
			this.out = out;
		}

		OutEndPoint(String outComponentPath, String outDataPath) {
			this.outPath = outDataPath;
			this.outComponentPath = outComponentPath;
		}
	}

	public static OutEndPoint outPoint(String outComponent, String outPath) {
		return new OutEndPoint(outComponent, outPath);
	}

	public static OutEndPoint outPoint(Mogram outExertion, String outPath) {
		return new OutEndPoint((Exertion)outExertion, outPath);
	}

	public static InEndPoint inPoint(String inComponent, String inPath) {
		return new InEndPoint(inComponent, inPath);
	}

	public static InEndPoint inPoint(Mogram inExertion, String inPath) {
		return new InEndPoint((Exertion)inExertion, inPath);
	}

	public static Pipe pipe(OutEndPoint outEndPoint, InEndPoint inEndPoint) {
		Pipe p = new Pipe(outEndPoint, inEndPoint);
		return p;
	}


	public static class Complement<T2> extends Entry<T2> {
		private static final long serialVersionUID = 1L;

		Complement(String path, T2 value) {
			super(path);
			this._2 = value;
		}
	}

	public static List<Provider> providers(Signature signature)
			throws SignatureException {
		ServiceTemplate st = new ServiceTemplate(null, new Class[] { signature.getServiceType() }, null);
		ServiceItem[] sis = Accessor.get().getServiceItems(st, null);
		if (sis == null)
			throw new SignatureException("No available providers of type: "
					+ signature.getServiceType().getName());
		List<Provider> servers = new ArrayList<Provider>(sis.length);
		for (ServiceItem si : sis) {
			servers.add((Provider) si.service);
		}
		return servers;
	}

	public static List<Class<?>> interfaces(Object obj) {
		if (obj == null)
			return null;
		return Arrays.asList(obj.getClass().getInterfaces());
	}

	public static Object provider(Signature signature)
			throws SignatureException {
		if (signature instanceof ObjectSignature && ((ObjectSignature)signature).getTarget() != null)
			return  ((ObjectSignature)signature).getTarget();
		else if (signature instanceof NetletSignature) {
			String source = ((NetletSignature)signature).getServiceSource();
			if(source != null) {
				try {
					ScriptExerter se = new ScriptExerter(System.out, null, Sorcer.getWebsterUrl(), true);
					se.readFile(new File(source));
					return se.parse();
				} catch (Throwable e) {
					throw new SignatureException(e);
				}
			}
		}
		Object target = null;
		Object provider = null;
		Class<?> providerType = signature.getServiceType();
		if (signature.getClass() == ObjectSignature.class) {
			target = ((ObjectSignature) signature).getTarget();
		}
		try {
			if (signature.getClass() == NetSignature.class) {
				provider = ((NetSignature) signature).getService();
				if (provider == null) {
					provider = Accessor.get().getService(signature);
					((NetSignature) signature).setProvider((Service)provider);
				}
			} else if (signature.getClass() == ObjectSignature.class) {
				if (target != null) {
					provider = target;
				} else if (Provider.class.isAssignableFrom(providerType)) {
					provider = providerType.newInstance();
				} else {
					if (signature.getSelector() == null &&
							(((ObjectSignature)signature).getInitSelector())== null) {
						provider = ((ObjectSignature) signature).getProviderType().newInstance();
					} else if (signature.getSelector().equals(((ObjectSignature)signature).getInitSelector())) {
						// utility class returns a utility (class) method
						provider = ((ObjectSignature) signature).getProviderType();
					} else {
						provider = sorcer.co.operator.instance(signature);
						((ObjectSignature)signature).setTarget(provider);
					}
				}
			} else if (signature instanceof ModelSignature) {
				if (target != null) {
					provider = target;
				} else if (ServiceModeler.class.isAssignableFrom(providerType)) {
					provider = providerType.newInstance();
				} else if (providerType.isInterface()
						&& (Modeler.class.isAssignableFrom(providerType))) {
					provider = Accessor.create().getService(signature);
				}
			} else if (signature instanceof EvaluationSignature) {
				provider = ((EvaluationSignature) signature).getEvaluator();
			}
		} catch (Exception e) {
			throw new SignatureException("No provider available", e);
		}
		return provider;
	}

	public static Condition condition(ParModel parcontext, String expression,
									  String... pars) {
		return new Condition(parcontext, expression, pars);
	}

	public static Condition condition(Closure condition) {
		return new Condition(condition);
	}

	public static <T> Condition condition(ConditionCollable<T> lambda) {
		return new Condition(lambda);
	}

	public static Condition condition(String expression,
									  String... pars) {
		return new Condition(expression, pars);
	}

	public static Condition condition(boolean condition) {
		return new Condition(condition);
	}

	public static OptMogram opt(String name, Exertion target) {
		return new OptMogram(name, target);
	}

	public static OptMogram opt(Condition condition,
								Exertion target) {
		return new OptMogram(condition, target);
	}


	public static OptMogram opt(String name, Condition condition,
								Exertion target) {
		return new OptMogram(name, condition, target);
	}

	public static AltMogram alt(OptMogram... exertions) {
		return new AltMogram(exertions);
	}

	public static AltMogram alt(String name, OptMogram... exertions) {
		return new AltMogram(name, exertions);
	}


	public static LoopMogram loop(Condition condition,
								  Mogram target) {
		return new LoopMogram(null, condition, target);
	}

	public static LoopMogram loop(int from, int to, Condition condition,
								  Mogram target) {
		return new LoopMogram(null, from, to, condition, target);
	}

	public static LoopMogram loop(int from, int to, Exertion target) {
		return new LoopMogram(null, from, to, null, target);
	}

	public static LoopMogram loop(String name, Condition condition,
								  Exertion target) {
		return new LoopMogram(name, condition, target);
	}

	public static Exertion exertion(Mappable mappable, String path)
			throws ContextException {
		Object obj = ((ServiceContext) mappable).asis(path);
		while (obj instanceof Mappable || obj instanceof Par) {
			try {
				obj = ((Evaluation) obj).asis();
			} catch (RemoteException e) {
				throw new ContextException(e);
			}
		}
		if (obj instanceof Exertion)
			return (Exertion) obj;
		else
			throw new NoneException("No such exertion at: " + path + " in: "
					+ mappable.getName());
	}

	public static Signature dispatcherSig(Signature signature) {
		((ServiceSignature)signature).addRank(Kind.DISPATCHER);
		return signature;
	}

	public static Signature modelSig(Signature signature) {
		((ServiceSignature)signature).addRank(new Kind[]{Kind.MODEL, Kind.TASKER});
		return signature;
	}

	public static Signature modelManagerSig(Signature signature) {
		((ServiceSignature)signature).addRank(Kind.MODEL, Kind.MODEL_MANAGER);
		return signature;
	}

	public static Signature optimizerSig(Signature signature) {
		((ServiceSignature)signature).addRank(Kind.OPTIMIZER, Kind.TASKER);
		return signature;
	}

	public static Signature explorerSig(Signature signature) {
		((ServiceSignature)signature).addRank(Kind.EXPLORER, Kind.TASKER);
		return signature;
	}

	public static Block block(Object...  items) throws ExertionException {
		List<Mogram> mograms = new ArrayList<Mogram>();
		String name = null;
		Signature sig = null;
		Context context = null;
		for (int i = 0; i < items.length; i++) {
			if (items[i] instanceof Exertion || items[i] instanceof EntModel) {
				mograms.add((Mogram) items[i]);
			} else if (items[i] instanceof Context) {
				context = (Context)items[i];
			} else if (items[i] instanceof Signature) {
				sig = (Signature)items[i];
			} else if (items[i] instanceof String) {
				name = (String)items[i];
			}
		}

		Block block;
		try {
			if (sig != null) {
				if (sig instanceof ObjectSignature)
					block = new ObjectBlock(name);
				else
					block = new NetBlock(name);
			} else {
				// default signature
//				block = new NetBlock(name);
				block = new ObjectBlock(name);
			}

			if (context != null) {
				// block scope is its own context
				block.setContext(context);
				((ServiceContext)context).setScope(context);
				// context for resetting to initial state after cleaning scopes
				((ServiceContext)context).setInitContext((Context)ObjectCloner.clone(context));
			}

			for (Mogram e :mograms) {
				block.addMogram(e);
			}
		} catch (Exception se) {
			throw new ExertionException(se);
		}
		//make sure it has ParModel as the data context
		ParModel pm = null;
		Context cxt = null;
		try {
			cxt = block.getDataContext();
			if (cxt == null) {
				cxt = new ParModel();
				block.setContext(cxt);
			}
			if (cxt instanceof ParModel) {
				pm = (ParModel)cxt;
			} else {
				pm = new ParModel("block context: " + cxt.getName());
				block.setContext(pm);
				pm.append(cxt);
				pm.setScope(pm);
				pm.setInitContext(context);
			}
			for (Mogram e : mograms) {
				if (e instanceof AltMogram) {
					List<OptMogram> opts = ((AltMogram) e).getOptExertions();
					for (OptMogram oe : opts) {
						oe.getCondition().setConditionalContext(pm);
					}
				} else if (e instanceof OptMogram) {
					((OptMogram)e).getCondition().setConditionalContext(pm);
				} else if (e instanceof LoopMogram) {
					if (((LoopMogram)e).getCondition() != null)
						((LoopMogram)e).getCondition().setConditionalContext(pm);
					Mogram target = ((LoopMogram)e).getTarget();
					if (target instanceof EvaluationTask && ((EvaluationTask)target).getEvaluation() instanceof Par) {
						Par p = (Par)((EvaluationTask)target).getEvaluation();
						p.setScope(pm);
						if (target instanceof Exertion && ((Exertion)target).getContext().getReturnPath() == null)
							((ServiceContext)((Exertion)target).getContext()).setReturnPath(p.getName());
					}
//				} else if (e instanceof VarTask) {
//					pm.append(((VarSignature)e.getProcessSignature()).getVariability());
				} else if (e instanceof EvaluationTask) {
					e.setScope(pm.getScope());
					if (((EvaluationTask)e).getEvaluation() instanceof Par) {
						Par p = (Par)((EvaluationTask)e).getEvaluation();
						pm.getScope().addPar(p);
//						pm.addPar(p);

					}
				} else if (e instanceof Exertion) {
					((ServiceContext)e.getDataContext()).setScope(pm.getScope());
					((Exertion)e).getDataContext().updateEntries(pm.getScope());
				}
			}
		} catch (Exception ex) {
			throw new ExertionException(ex);
		}
		return block;
	}

	public static class Jars {
		public String[] jars;

		Jars(String... jarNames) {
			jars = jarNames;
		}
	}

	public static class CodebaseJars  {
		public String[] jars;

		CodebaseJars(String... jarNames) {
			jars = jarNames;
		}
	}

	public static class Impl {
		public String className;

		Impl(String className) {
			this.className = className;
		}
	}

	public static class Configuration {
		public String configuration;

		Configuration(final String configuration) {
			this.configuration = configuration;
		}
	}

	public static class WebsterUrl {
		public String websterUrl;

		WebsterUrl(String websterUrl) {
			this.websterUrl = websterUrl;
		}
	}

	public static class Multiplicity {
		public int multiplicity;
		public int maxPerCybernode;

		Multiplicity(int multiplicity) {
			this.multiplicity = multiplicity;
		}

		Multiplicity(int multiplicity, PerNode perNode) {
			this(multiplicity, perNode.number);
		}

		Multiplicity(int multiplicity, int maxPerCybernode) {
			this.multiplicity = multiplicity;
			this.maxPerCybernode = maxPerCybernode;
		}
	}

	public static class Idle {
		public final int idle;

		Idle(final int idle) {
			this.idle = idle;
		}

		Idle(final String idle) {
			this.idle = ServiceDeployment.parseInt(idle);
		}
	}

	public static class PerNode {
		public final int number;

		PerNode(final int number) {
			this.number = number;
		}
	}

	public static class IP {
		final Set<String> ips = new HashSet<String>();
		boolean exclude;

		public IP(final String... ips) {
			Collections.addAll(this.ips, ips);
		}

		void setExclude(final boolean exclude) {
			this.exclude = exclude;
		}

		public String[] getIps() {
			return ips.toArray(new String[ips.size()]);
		}
	}

	public static class Arch {
		final String arch;

		public Arch(final String arch) {
			this.arch = arch;
		}

		public String getArch() {
			return arch;
		}
	}

	public static class OpSys {
		final Set<String> opSys = new HashSet<String>();

		public OpSys(final String... opSys) {
			Collections.addAll(this.opSys, opSys);
		}

		public String[] getOpSys() {
			return opSys.toArray(new String[opSys.size()]);
		}
	}

	public static PerNode perNode(int number) {
		return new PerNode(number);
	}

	public static Jars classpath(String... jarNames) {
		return new Jars(jarNames);
	}

	public static CodebaseJars codebase(String... jarNames) {
		return new CodebaseJars(jarNames);
	}

	public static Impl implementation(String className) {
		return new Impl(className);
	}

	public static WebsterUrl webster(String WebsterUrl) {
		return new WebsterUrl(WebsterUrl);
	}

	public static Configuration configuration(String configuration) {
		return new Configuration(configuration);
	}

	public static Multiplicity maintain(int multiplicity) {
		return new Multiplicity(multiplicity);
	}

	public static Multiplicity maintain(int multiplicity, int maxPerCybernode) {
		return new Multiplicity(multiplicity, maxPerCybernode);
	}

	public static Multiplicity maintain(int multiplicity, PerNode perNode) {
		return new Multiplicity(multiplicity, perNode);
	}

	public static Idle idle(String idle) {
		return new Idle(idle);
	}

	public static Idle idle(int idle) {
		return new Idle(idle);
	}

	public static IP ips(String... ips) {
		return new IP(ips);
	}

	public static IP ips_exclude(String... ips) {
		IP ip = new IP(ips);
		ip.exclude = true;
		return ip;
	}

	public static Arch arch(String arch) {
		return new Arch(arch);
	}

	public static OpSys opsys(String... opsys) {
		return new OpSys(opsys);
	}

	public static <T> ServiceDeployment deploy(T... elems) {
		ServiceDeployment deployment = new ServiceDeployment();
		for (Object o : elems) {
			if (o instanceof Jars) {
				deployment.setClasspathJars(((Jars) o).jars);
			} else if (o instanceof CodebaseJars) {
				deployment.setCodebaseJars(((CodebaseJars) o).jars);
			} else if (o instanceof Configuration) {
				deployment.setConfig(((Configuration) o).configuration);
			} else if (o instanceof Impl) {
				deployment.setImpl(((Impl) o).className);
			} else if (o instanceof Multiplicity) {
				deployment.setMultiplicity(((Multiplicity) o).multiplicity);
				deployment.setMaxPerCybernode(((Multiplicity) o).maxPerCybernode);
			} else if(o instanceof ServiceDeployment.Type) {
				deployment.setType(((ServiceDeployment.Type) o));
			} else if (o instanceof Idle) {
				deployment.setIdle(((Idle) o).idle);
			} else if (o instanceof PerNode) {
				deployment.setMaxPerCybernode(((PerNode)o).number);
			} else if (o instanceof IP) {
				IP ip = (IP)o;
				for(String ipAddress : ip.getIps()) {
					try {
						InetAddress inetAddress = InetAddress.getByName(ipAddress);
						if(inetAddress.isReachable(1000)) {
							logger.warn(getWarningBanner("The signature declares an ip address or hostname.\n" +
									ipAddress+" is not reachable on the current network"));
						}
					} catch (Exception e) {
						logger.warn(getWarningBanner(ipAddress+" is not found on the current network.\n"
								+e.getClass().getName()+": "+e.getMessage()));
					}
				}
				if(ip.exclude) {
					deployment.setExcludeIps(ip.getIps());
				} else {
					deployment.setIps(ip.getIps());
				}
			} else if (o instanceof Arch) {
				deployment.setArchitecture(((Arch)o).getArch());
			} else if (o instanceof OpSys) {
				deployment.setOperatingSystems(((OpSys) o).getOpSys());
			} else if (o instanceof WebsterUrl) {
				deployment.setWebsterUrl(((WebsterUrl)o).websterUrl);
			}
		}
		return deployment;
	}

	public static Exertion add(Exertion compound, Exertion component)
			throws ExertionException {
		compound.addMogram(component);
		return compound;
	}

	public static Block block(Loop loop, Exertion exertion)
			throws ExertionException, SignatureException {
		List<String> names = loop.getNames(exertion.getName());
		Block block;
		if (exertion instanceof NetTask || exertion instanceof NetJob
				|| exertion instanceof NetBlock) {
			block = new NetBlock(exertion.getName() + "-block");
		} else {
			block = new ObjectBlock(exertion.getName() + "-block");
		}
		Exertion xrt = null;
		for (String name : names) {
			xrt = (Exertion) ObjectCloner.cloneAnnotatedWithNewIDs(exertion);
			((ServiceExertion) xrt).setName(name);
			block.addMogram(xrt);
		}
		return block;
	}

	public static Version version(String ver) {
		return new Version(ver);
	}



	private static String getWarningBanner(String message) {
		StringBuilder builder = new StringBuilder();
		builder.append("\n****************************************************************\n");
		builder.append(message).append("\n");
		builder.append("****************************************************************\n");
		return builder.toString();
	}

}
