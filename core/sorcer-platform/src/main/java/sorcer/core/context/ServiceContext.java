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

package sorcer.core.context;

import net.jini.core.transaction.Transaction;
import net.jini.core.transaction.TransactionException;
import net.jini.id.Uuid;
import net.jini.id.UuidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.co.tuple.InputEntry;
import sorcer.co.tuple.OutputEntry;
import sorcer.co.tuple.Tuple2;
import sorcer.core.SorcerConstants;
import sorcer.core.context.model.ent.*;
import sorcer.core.context.model.ent.Proc;
import sorcer.core.context.node.ContextNode;
import sorcer.core.context.node.ContextNodeException;
import sorcer.core.exertion.NetTask;
import sorcer.core.invoker.ServiceInvoker;
import sorcer.core.monitor.MonitorUtil;
import sorcer.core.provider.Provider;
import sorcer.core.provider.ServiceProvider;
import sorcer.core.signature.NetSignature;
import sorcer.core.signature.ServiceSignature;
import sorcer.eo.operator;
import sorcer.service.*;
import sorcer.service.Signature.Direction;
import sorcer.service.Signature.ReturnPath;
import sorcer.service.modeling.Model;
import sorcer.service.Domain;
import sorcer.service.modeling.Variability;
import sorcer.util.ObjectCloner;
import sorcer.util.SorcerUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static sorcer.eo.operator.sig;
import static sorcer.eo.operator.task;

/**
 * Implements the base-level service context interface {@link Context}.
 */
public class ServiceContext<T> extends ServiceMogram implements
		Context<T>, AssociativeContext<T>, Contexter<T>, SorcerConstants {

	private static final long serialVersionUID = 3311956866023311727L;
	protected Map<String, T> data = new ConcurrentHashMap<String, T>();
	protected String subjectPath = "";
	protected Object subjectValue = "";
	// default eval new ReturnPath(Context.RETURN);
	protected ReturnPath<T> returnPath;
	protected ReturnPath<T> returnJobPath;

	// for calls by reflection for 'args' Object[] setValue the path
	// or use the default one: Context.ARGS
	//protected String argsPath = Context.ARGS;
	protected String argsPath;
	protected String parameterTypesPath;

	// a flag for the context to be shared
	// for data piping see: map, connect, pipe
	protected boolean isShared = false;
	protected String prefix = "";
	protected List<EntryList> entryLists;
	/**
	 * metacontext: key is a metaattribute and eval is a map of
	 * path/metapath args
	 */
	protected Map<String, Map<String,String>> metacontext;
	protected Context initContext;

	/** The exertion that uses this context */
	protected ServiceExertion exertion;
	protected String currentPrefix;
	protected boolean isFinalized = false;
	protected Variability.Type type = Variability.Type.CONTEXT;
	Signature.Direction direction = Signature.Direction.INOUT;

	/**
	 * For persistence layers to differentiate with saved context already
	 * associated to task or not.
	 */
	public boolean isPersistantTaskAssociated = false;

	/** EMPTY LEAF NODE ie. node with no data and not empty string */
	public final static String EMPTY_LEAF = ":Empty";

	// this class logger
	static Logger logger = LoggerFactory.getLogger(ServiceContext.class);

	/**
	 * Default constructor for the ServiceContext class. The constructor calls the method init, 
	 * defines the the service context name sets the root name to a blank string creates a new 
	 * hash tables for path identifications, delPath, and linked paths. The constructor creates the 
	 * context identification number via the UUID factory generate method.
	 */
	public ServiceContext() {
		this(defaultName + count++);
	}

	/**
	 * Constructor for Service Context class. It calls on the default constructor
	 * @param name
	 * @see ServiceContext
	 */
	public ServiceContext(String name) {
		super();
        initContext();
        if (name == null || name.length() == 0) {
			this.name = defaultName + count++;
		} else {
			this.name = name;
		}
		mogramId = UuidFactory.generate();
		mogramStrategy = new ModelStrategy(this);
		creationDate = new Date();
	}

	public ServiceContext(String name, Signature builder) {
		this(name);
		this.builder = builder;
	}

	public ServiceContext(String subjectPath, Object subjectValue) {
		this(subjectPath);
		this.subjectPath = subjectPath;
		this.subjectValue = subjectValue;
	}

	public ServiceContext(String name, String subjectPath, Object subjectValue) {
		this(name);
		this.subjectPath = subjectPath;
		this.subjectValue = subjectValue;
	}

	public ServiceContext(Context<T> context) throws ContextException {
		this(context.getSubjectPath(), context.getSubjectValue());
		ServiceContext cxt = (ServiceContext)context;
		String path;
		T obj;
		Iterator i = cxt.keyIterator();
		while (i.hasNext()) {
			path = (String) i.next();
			obj = (T) cxt.get(path);
			if (obj == null)
				put(path, (T) none);
			else
				put(path, obj);
		}

		setMetacontext(cxt.getMetacontext());
		// copy instance fields
		mogramId = (Uuid)cxt.getId();
		parentPath = cxt.getParentPath();
		parentId = cxt.getParentId();
		creationDate = new Date();
		description = cxt.getDescription();
		scope = cxt.getScope();
		initContext = ((ServiceContext) cxt).getInitContext();
		ownerId = cxt.getOwnerId();
		subjectId = cxt.getSubjectId();
		projectName = cxt.getProjectName();
		accessClass = cxt.getAccessClass();
		isExportControlled = cxt.isExportControlled();
		goodUntilDate = cxt.getGoodUntilDate();
		domainId = cxt.getDomainId();
		subdomainId = cxt.getSubdomainId();
		domainName = cxt.getDomainName();
		subdomainName = cxt.getSubdomainName();
		exertion = (ServiceExertion) cxt.getMogram();
		principal = cxt.getPrincipal();
		isPersistantTaskAssociated = cxt.isPersistantTaskAssociated;
	}

	public ServiceContext(List<Identifiable> objects) throws ContextException {
		for (Identifiable obj : objects) {
			putValue(obj.getName(), obj);
		}
	}

	public ServiceContext(Object... objects) throws ContextException {
		setArgsPath(Context.PARAMETER_VALUES);
		setArgs(objects);
		setParameterTypesPath(Context.PARAMETER_TYPES);
		Class[] parTypes = new Class[objects.length];
		for (int i = 0; i < objects.length; i++) {
			parTypes[i] = objects[i].getClass();
		}
	}

	/**
	 * Initializes the service context class by allocating storage for all
	 * simple and composite attributes and their associations. It creates system
	 * data attributes: SORCER_TYPE - dnt, CONTEXT_PARAMETER - cp, ACTION -
	 * action.
	 * <p>
	 * A 'metacontext' map stores all data attribute definitions in an internal
	 * 'metacontext' map with a key being an attribute
	 * mapped to the corresponding attribute descriptor. The attribute
	 * descriptor for a simple attribute is the attribute name itself, and for a
	 * composite attribute the descriptor is an APS (association path separator)
	 * separated list of component attributes. A 'metacontext' map contains all
	 * simple attributes and component attributes (keys) associations with the
	 * corresponding map holding associations between between a path (key) and
	 * the eval of attribute (key in 'metacontext').
	 * <p>
	 * The usage of metacontext is illustrated as follows:
	 * a single attribute - 'tag'; cxt.tag("arg/x1", "tag|stress");
	 * and get tagged eval at arg/x1: cxt.getMarkedValues("tag|stress"));
	 * relation - 'triplet|path|info|_3', 'triplet' is a relation name and path, _3, and _3
	 * are component attributes; cxt.tag("arg/x3", "triplet|mike|w|sobol");
	 * and get tagged eval at arg/x3: cxt.getMarkedValues("triplet|mike|w|sobol"));
	 */
    protected void initContext() {
		super.init();
		data = new ConcurrentHashMap<String, T>();
		metacontext = new HashMap<String, Map<String, String>>();
		metacontext.put(SorcerConstants.CONTEXT_ATTRIBUTES, new HashMap());

		// specify four SORCER standard composite attributes
		try {
			// default relation tags: tag, assoc, and triplet
			setAttribute("tag");
			setAttribute("assoc|key|value");
			setAttribute("triplet|1|2|3");
			// context path tag
			setAttribute(PATH_PAR);
			// annotating input output files associated with source applications
			setCompositeAttribute(DATA_NODE_TYPE + APS + APPLICATION + APS
					+ FORMAT + APS + MODIFIER);
			// directional attributes with the context ID
			setCompositeAttribute(CONTEXT_PARAMETER + APS + DIRECTION + APS
					+ PATH + APS + CONTEXT_ID + APS + VAL_CLASS);
			// relationship to providers
			setCompositeAttribute(ACTION + APS + PROVIDER_NAME + APS
					+ INTERFACE + APS + SELECTOR);
			// operand positioning (OOP) for operators by index
			setCompositeAttribute(OPP + APS + DIRECTION + APS + INDEX);
			// the variable node fiType relationship (var name and its fiType) in
			// Analysis Models: vnt|var|vt
			setCompositeAttribute(VAR_NODE_TYPE + APS + VAR + APS + VT);
			dbUrl = "sos://sorcer.service.DatabaseStorer";
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public Model newInstance() throws SignatureException {
		return (Model) sorcer.co.operator.instance(builder);
	}

	public Context clearReturnPath() throws ContextException {
		ReturnPath rp = getReturnPath();
		if (rp != null && rp.path != null)
			removePath(rp.path);
		return this;
	}

	public ServiceContext clearScope() throws ContextException {
//		clearReturnPath
		return this;
	}

	@Override
	public List<ThrowableTrace> getExceptions() {
		if (exertion != null)
			// compatibility for contexts with mograms
			return exertion.getExceptions();
		else
			return ((ModelStrategy)mogramStrategy).getAllExceptions();
	}

	@Override
	public List<String> getTrace() {
		return ((ModelStrategy)mogramStrategy).getTraceList();
	}

	@Override
	public List<ThrowableTrace> getAllExceptions() {
		List<ThrowableTrace> exertExceptions;
		if (exertion != null)
			exertExceptions = exertion.getExceptions();
		else
			exertExceptions = new ArrayList<ThrowableTrace>();

		if (mogramStrategy == null)
			return ((ModelStrategy)mogramStrategy).getAllExceptions();
		else {
			exertExceptions.addAll(((ModelStrategy)mogramStrategy).getAllExceptions());
		}
		return exertExceptions;
	}

	@Override
	public boolean isMonitorable() {
		return mogramStrategy.isMonitorable();
	}

	public Context getInitContext() {
		return initContext;
	}

	public void setInitContext(Context initContext) {
		this.initContext = initContext;
	}

	public Exertion getMogram() {
		return exertion;
	}

	public void setExertion(Exertion exertion) {
		if (exertion == null || exertion instanceof Exertion)
			this.exertion = (ServiceExertion) exertion;
	}

	public T getReturnValue(Arg... entries) throws RemoteException,
			ContextException {
		T val = null;
		ReturnPath rp = returnPath;
		for (Arg a : entries) {
			if (a instanceof ReturnPath)
				rp = (ReturnPath)a;
		}
		if (rp != null) {
			try {
				if (rp.path != null && rp.path.equals(Signature.SELF)) {
					return (T) this;
				} else if (rp.outPaths != null && rp.outPaths.length > 0) {
					val = (T) getSubcontext(rp.outPaths);
				} else {
					if (rp.type != null) {
						val = (T) rp.type.cast(getValue(rp.path));
					}  else
						val= (T) getValue0(rp.path);
				}
			} catch (Exception e) {
				throw new ContextException(e);
			}
		}
		if (val instanceof Evaluation && isRevaluable) {
			val = ((Evaluation<T>) val).getValue(entries);
		} else if ((val instanceof Paradigmatic)
				&& ((Paradigmatic) val).isModeling()) {
			val = ((Evaluation<T>) val).getValue(entries);
		}
		return val;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws
	 */
	public Object getValue0(String path) throws ContextException {
		Object result = get(path);
		if (result instanceof ContextLink) {
			String offset = ((ContextLink) result).getOffset();
			Context linkedCntxt = ((ContextLink) result).getContext();
			result = linkedCntxt.getValue(offset);
		}
		if (result == null) {
			// could be in a linked context
			List<String> paths = localLinkPaths();
			int len;
			for (String linkPath : paths) {
				ContextLink link = null;
				link = (ContextLink) get(linkPath);
				String offset = link.getOffset();
				int index = offset.lastIndexOf(CPS);
				String extendedLinkPath = linkPath;
				if (index < 0) {
					if (offset.length() > 0)
						extendedLinkPath = linkPath + CPS + offset;
				} else
					extendedLinkPath = linkPath + offset.substring(index);
				len = extendedLinkPath.length();
				if (path.startsWith(extendedLinkPath)
						&& (path.indexOf(CPS, len) == len || path.length() > len)) {
					// looking for something in this linked context
					String keyInLinkedCntxt = path.substring(len + 1);
					if (offset.length() > 0)
						keyInLinkedCntxt = offset + path.substring(len);
					Context linkedCntxt;
					linkedCntxt = getLinkedContext(link);
					result = linkedCntxt.getValue(keyInLinkedCntxt);
					break;
				}
			}
		}
		return result;
	}

	/**
	 * Returns an enumeration of all paths marking input data nodes.
	 *
	 * @return enumeration of marked input paths
	 * @throws ContextException
	 */
	public Enumeration inPaths() throws ContextException {
		String inAssoc = DIRECTION + SorcerConstants.APS + DA_IN;
		String inoutAssoc = DIRECTION + SorcerConstants.APS + DA_INOUT;
		String[] inPaths = Contexts.getMarkedPaths(this, inAssoc);
		String[] inoutPaths = Contexts.getMarkedPaths(this, inoutAssoc);
		Vector inpaths = new Vector();
		if (inPaths != null)
			for (int i = 0; i < inPaths.length; i++)
				inpaths.add(inPaths[i]);
		if (inoutPaths != null)
			for (int i = 0; i < inoutPaths.length; i++)
				inpaths.add(inoutPaths[i]);
		return inpaths.elements();
	}

	/**
	 * Returns a enumeration of all paths marking output data nodes.
	 *
	 * @return enumeration of marked output paths
	 * @throws ContextException
	 */
	public Enumeration outPaths() throws ContextException {
		String outAssoc = DIRECTION + SorcerConstants.APS + DA_OUT;
		String[] outPaths = Contexts.getMarkedPaths(this, outAssoc);

		Vector outpaths = new Vector();
		if (outPaths != null)
			for (int i = 0; i < outPaths.length; i++)
				outpaths.add(outPaths[i]);

		return outpaths.elements();
	}

	@Override
	public T getSoftValue(String path) throws ContextException {
		T val = get(path);
		if (val == null) {
			try {
				int index = path.lastIndexOf(SorcerConstants.CPS);
				String attribute = path.substring(index+1);
				return getValueEndsWith(attribute);
			} catch (Exception e) {
				throw new ContextException(e);
			}
		} else {
			return val;
		}
	}

	// we assume that a path ending with name refers to its eval
	public T getValueEndsWith(String name) throws EvaluationException,
			RemoteException {
		T val = null;
		Iterator<Map.Entry<String, T>> i = entryIterator();
		Map.Entry<String, T> entry;
		while (i.hasNext()) {
			entry = i.next();
			if (entry.getKey().endsWith(name)) {
				val = entry.getValue();
				if (val instanceof Evaluation && isRevaluable)
					try {
						val = ((Evaluation<T>) val).getValue();
					} catch (ContextException e) {
						throw new EvaluationException(e);
					}
			}
		}
		return val;
	}

	public Object getValueStartsWith(String name) throws EvaluationException,
			RemoteException {
		Object val = null;
		Iterator<Map.Entry<String, T>> i = entryIterator();
		Map.Entry<String, T> entry;
		while (i.hasNext()) {
			entry = i.next();
			if (entry.getKey().startsWith(name)) {
				val = entry.getValue();
				if (val instanceof Evaluation && isRevaluable)
					try {
						val = ((Evaluation) val).getValue();
					} catch (ContextException e) {
						throw new EvaluationException(e);
					}
			}
		}
		return val;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.AssociativeContext#putValue(java.lang.String, java.lang.Object)
	 */
	@Override
	public T putValue(final String path, Object value) throws ContextException {
		if(path==null)
			throw new IllegalArgumentException("path must not be null");
		// first test if path is in a linked context
		List<String> paths = localLinkPaths();
		for (String linkPath : paths) {
			// path has to start with linkPath+last_piece_of_offset
			ContextLink link = null;
			link = (ContextLink) get(linkPath);
			String offset = link.getOffset();
			int index = offset.lastIndexOf(CPS);
			// extendedLinkPath is the linkPath + the last piece of
			// the offset. We drop down in the link only if there is
			// a match here. This is required to distinguish from,
			// say, linkPath + m (where m is not the last piece of the
			// offset), which should not go into the linked context,
			// but in the linking context.
			//
			// be sure to handle these cases:
			// offset = "" -- the whole context linked
			// offset has no CPS, as in offset="abc"
			// offset has a CPS, as in offset="ab/c"
			String extendedLinkPath = linkPath;
			if (index < 0) {
				if (offset.length() > 0)
					extendedLinkPath = linkPath + CPS + offset;
			} else
				extendedLinkPath = linkPath + offset.substring(index);
			int len = extendedLinkPath.length();
			if (path.startsWith(extendedLinkPath)
					&& (path.indexOf(CPS, len) == len || path.length() == len)) {
				String keyInLinkedCntxt;
				// for this path, find path in linked context
				if (offset.equals(""))
					keyInLinkedCntxt = path.substring(len + 1);
				else
					keyInLinkedCntxt = offset + path.substring(len);
				Context linkedCntxt = getLinkedContext(link);
				return (T)linkedCntxt.putValue(keyInLinkedCntxt, value);
			}
		}
		T obj = null;
		if (value == null)
			obj = put(path, (T)none);
		else {
			obj = get(path);
//			if (SdbUtil.isSosURL(obj)) {
//				try {
//				SdbUtil.update((URL)obj, eval);
//			} catch (Exception ex) {
//				throw new ContextException(ex);
//			}
//		} else
			if (obj instanceof Reactive && obj instanceof Setter) {
				try {
					((Setter)obj).setValue(value);
				} catch (RemoteException ex) {
					throw new ContextException(ex);
				}
			} else {
				obj = put(path, (T)value);
			}
		}
		return obj;
	}

	public Object putValue(String path, Object value, String association)
			throws ContextException {
		// for the special case where the attribute-eval pair or
		// (meta)association can be represented as a single string
		T obj = putValue(path, value);
		mark(path, association);

		if ((value instanceof ContextNode)
				&& association.startsWith(CONTEXT_PARAMETER))
			((ContextNode) value).setDA(SorcerUtil
					.secondToken(association, APS));
		return obj;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see sorcer.base.ServiceContext#map(java.lang.String, java.lang.String,
	 * sorcer.base.ServiceContext)
	 */
	public void map(String fromPath, String toPath, Context toContext)
			throws ContextException {
		isShared = true;
		Contexts.map(fromPath, this, toPath, toContext);
	}

	/**
	 * <p>
	 * Contexts with mapped paths {@link #map} are indicated by the shared flag.
	 * </p>
	 *
	 * @return the isShared
	 */
	public boolean isShared() {
		return isShared;
	}

	public void removeLink(String path) throws ContextException {
		// locate the context and context path for this key
		Object[] map = getContextMapping(path);// , true); // don't descend
		ServiceContext cntxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];
		if (cntxt.get(mappedKey) instanceof ContextLink) {
			cntxt.remove(mappedKey);
			cntxt.put(mappedKey, Context.EMPTY_LEAF);
		} else
			throw new ContextException("path = \"" + path
					+ "\" does not point to a ContextLink object");
	}

	public Object putLink(String name, String path, Context cntxt, String offset)
			throws ContextException {
		// insert a ContextLink (a.k.a. a symbolic link) to cntxt
		// this makes this.getValue(path) == cntxt.getValue(offset)
		if (path == null)
			throw new ContextException("ERROR: path is null");

		/*
		 * Allow adding to non context-leaf nodes // Check if this is a
		 * context-leaf node; throw exception otherwise; // this policy ensures
		 * namespace uniqueness; otherwise, rules for // aliased or shadowed
		 * paths must be devised. Enumeration e = getPaths(); String path; int
		 * len; while (e.hasMoreElements()) { path = (String)e.nextElement(); if
		 * (path.startsWith(key)) { len = path.length(); if
		 * (path.indexOf(CPS,len) == len) { throw new ContextException("ERROR:
		 * path \""+path+"\" is not a context-leaf node; remove dependent
		 * context-leaf nodes or choose another path in \""+getName()+"\"
		 * first"); } } }
		 */

		if (cntxt == null)
			throw new ContextException(
					"Failed to create ContextLink:  context to link is null");
		if (offset == null)
			throw new ContextException(
					"Failed to create ContextLink:  offset is null");

		String extendedLinkPath = path;
		if (offset.length() > 0)
			extendedLinkPath = path + CPS + offset;
		Iterator<String> paths = getPaths().iterator();
		while (paths.hasNext()) {
			if (paths.next().startsWith(extendedLinkPath))
				throw new ContextException(
						"Failed to create ContextLink:  a path already exists that starts with \""
								+ extendedLinkPath
								+ "\".  This link cannot be added here.");
		}
		Object[] map = cntxt.getContextMapping(offset);
		if (map[0] == null || map[1] == null)
			throw new ContextException("ERROR: path \"" + offset
					+ "\" in context \"" + cntxt.getName() + "\" is invalid");

		// using map will collapse redundant links
		ContextLink link = new ContextLink((Context) map[0], (String) map[1]);
		// Put the link count against the path in the context
		if (name == null || name.length() == 0)
			link.setName(cntxt.getName());
		else
			link.setName(name);
		return putValue(path, (T)link);
	}

	public Object putLink(String path, Context cntxt, String offset)
			throws ContextException {
		return putLink("", path, cntxt, offset);
	}

	public Object putLink(String path, Context cntxt)
			throws ContextException {
		return putLink("", path, cntxt, "");
	}

	@Override
	public Object remove(Object path) {
		return data.remove(path);
	}

	public Link getLink(String path) throws ContextException {
		ContextLink result = null;
		Object value;
		if (path == null)
			return null;
		value = get(path);
		if (value != null) {
			if (value instanceof ContextLink)
				result = (ContextLink) value;
		} else if (value == null) {
			// could be in a linked context
			List<String> paths = localLinkPaths();
			int len;
			for (String linkPath : paths) {
				ContextLink link = (ContextLink) get(linkPath);
				String offset = link.getOffset();
				int index = offset.lastIndexOf(CPS);
				String extendedLinkPath = linkPath;
				if (index < 0) {
					if (offset.length() == 0)
						extendedLinkPath = linkPath + CPS + offset;
				} else
					extendedLinkPath = linkPath + offset.substring(index);
				len = extendedLinkPath.length();
				if (path.startsWith(extendedLinkPath)
						&& (path.indexOf(CPS, len) == len || path.length() == len)) {
					// looking for something in this linked context
					String keyInLinkedCntxt;
					if (offset.equals(""))
						keyInLinkedCntxt = path.substring(len + 1);
					else
						keyInLinkedCntxt = offset + path.substring(len);
					Context linkedCntxt = getLinkedContext(link);
					result = (ContextLink) linkedCntxt
							.getLink(keyInLinkedCntxt);
					break;
				}
			}
		}
		return result;
	}

	/*
	 * Returns array containing the ServiceContext in which path is found and
	 * the absolute path in that context.
	 */
	public Object[] getContextMapping(String path) throws ContextException {
		Object[] result = new Object[2];
		Object value;
		if (path == null)
			return null;
		value = get(path);
		if (value != null) {
			result[0] = this;
			result[1] = path;
		} else if (value == null) {
			List<String> paths = localLinkPaths();
			int len;
			for (String linkPath : paths) {
				ContextLink link = (ContextLink) get(linkPath);
				String offset = link.getOffset();
				int index = offset.lastIndexOf(CPS);
				String extendedLinkPath;
				if (index < 0) {
					extendedLinkPath = linkPath + CPS + offset;
				} else
					extendedLinkPath = linkPath + offset.substring(index);
				len = extendedLinkPath.length();
				if (path.startsWith(extendedLinkPath)
						&& (path.indexOf(CPS, len) == len || path.length() == len)) {
					String keyInLinkedCntxt;
					if (offset.equals(""))
						keyInLinkedCntxt = path.substring(len + 1);
					else
						keyInLinkedCntxt = offset + path.substring(len);

					Context linkedCntxt = getLinkedContext(link);
					result = linkedCntxt.getContextMapping(keyInLinkedCntxt);
					break;
				}
			}
		}
		if (result[0] == null) {
			// the path belongs in this context, but is not in the
			// hashtable. We'll return the map anyway.
			// System.out.println("getContextMap: no mapping");
			result[0] = this;
			result[1] = path; // this is null
		}
		return result;
	}

	private Map getDataAttributeMap() {
		return  metacontext.get(SorcerConstants.CONTEXT_ATTRIBUTES);
	}

	public Set<String>  localAttributes() {
		return metacontext.get(SorcerConstants.CONTEXT_ATTRIBUTES).keySet();
	}

	protected Map getDataAttributeMap(String attributeName) {
		if (isLocalAttribute(attributeName))
			return metacontext.get(attributeName);
		else
			return null;
	}

	public void setAttribute(String descriptor) throws ContextException {
		String[] tokens = SorcerUtil.tokenize(descriptor, APS);
		if (tokens.length == 1)
			setComponentAttribute(descriptor);
		else
			setCompositeAttribute(descriptor);
	}

	public void setComponentAttribute(String attribute) {
		if (attribute.startsWith(PRIVATE) && attribute.endsWith(PRIVATE))
			return;
		getDataAttributeMap().put(attribute, attribute);
	}

	public void setCompositeAttribute(String descriptor)
			throws ContextException {
		// Register a composite ("composite|<component attributes>")
		// with this ServiceContext
		String composite = SorcerUtil.firstToken(descriptor, APS);
		if (composite.startsWith(PRIVATE) && composite.endsWith(PRIVATE))
			throw new ContextException("Illegal metaattribute name");
		String components = descriptor.substring(composite.length() + 1);
		getDataAttributeMap().put(composite, components);
		StringTokenizer st = new StringTokenizer(components, APS);
		String attribute;
		while (st.hasMoreTokens()) {
			attribute = st.nextToken();
			if (!isSingletonAttribute(attribute))
				setComponentAttribute(attribute);
		}
	}

	public boolean isLocalAttribute(String attribute) {
		// All Attributes are stored in this hashtable
		if (attribute.startsWith(PRIVATE) && attribute.endsWith(PRIVATE))
			return false;
		return getDataAttributeMap().containsKey(attribute);
	}

	public boolean isLocalSingletonAttribute(String attributeName) {
		// All Attributes are stored in the localContextAttributes hashtable
		// and singletons have key equal to the eval
		return isLocalAttribute(attributeName)
				&& getDataAttributeMap().get(attributeName).equals(
				attributeName);
	}

	public boolean isLocalMetaattribute(String attributeName) {
		// Metaattributes are stored in the localContextAttributes
		// hashtable and have key equal to the attribute setValue, not the
		// eval as with singleton attributes
		return isLocalAttribute(attributeName)
				&& !getDataAttributeMap().get(attributeName).equals(
				attributeName);
	}

	public boolean isAttribute(String attributeName) throws ContextException {
		boolean result = isLocalAttribute(attributeName);
		if (!result) {
			// not an attribute of the top-level context; check all
			// top-level linked contexts (which in turn will check
			// their top-level contexts, etc. until a match is found or
			// all contexts are exhausted )
			Enumeration e = null;
			List<Link> links = localLinks();
			for(Link link : links) {
				result = getLinkedContext((ContextLink)link).isAttribute(attributeName);
				if (result)
					break;
			}
		}
		return result;
	}

	public boolean isSingletonAttribute(String attributeName)
			throws ContextException {
		// All Attributes are stored in the localContextAttributes hashtable
		// and singletons have key equal to the eval
		boolean result = isLocalAttribute(attributeName)
				&& getDataAttributeMap().get(attributeName).equals(
				attributeName);
		if (!result) {
			// not an attribute of the top-level context; check all
			// top-level linked contexts (which in turn will check
			// their top-level contexts, etc. until a match is found or
			// all contexts are exhausted)
			List<Link> links = localLinks();
			for(Link link : links) {
				result = getLinkedContext((ContextLink)link).isSingletonAttribute(
						attributeName);
				if (result)
					break;
			}
		}
		return result;
	}

	public boolean isMetaattribute(String attributeName)
			throws ContextException {
		// Metaattributes are stored in the localContextAttributeisLos
		// hashtable and have key equal to the attribute setValue, not the
		// eval as with singleton attributes
		boolean result = isLocalAttribute(attributeName)
				&& !getDataAttributeMap().get(attributeName).equals(
				attributeName);
		if (!result) {
			// not an attribute of the top-level context; check all
			// top-level linked contexts (which in turn will check
			// their top-level contexts, etc. until a match is found or
			// all contexts are exhausted)
			List<Link> links = localLinks();
			for (Link link : links) {
				result = getLinkedContext((ContextLink)link).isMetaattribute(attributeName);
				if (result)
					break;
			}
		}
		return result;
	}

	public String getAttributeValue(String path, String attributeName)
			throws ContextException {
		String attr;
		attr = getSingletonAttributeValue(path, attributeName);
		if (attr != null)
			return attr;
		return getMetaattributeValue(path, attributeName);
	}

	public String getSingletonAttributeValue(String path, String attributeName)
			throws ContextException {
		String val = null;
		Hashtable table;

		// locate the context and context path for this key
		Object[] map = getContextMapping(path);
		ServiceContext cntxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];

		if (cntxt.isSingletonAttribute(attributeName)) {
			table = (Hashtable) cntxt.metacontext.get(attributeName);
			if (table != null) {
				val = (String) table.get(mappedKey);
			}
		}
		return val;
	}

	public String getMetaattributeValue(String path, String attributeName)
			throws ContextException {
		String attrValue, result = null;

		// locate the context and context path for this key
		Object[] map = getContextMapping(path);
		Context cntxt = (Context) map[0];
		String mappedKey = (String) map[1];

		String metapath = cntxt.getLocalMetapath(attributeName);

		if (metapath != null) {
			String[] attrs = SorcerUtil.tokenize(metapath, APS);
			StringBuffer sb = new StringBuffer();
			int count = 0;
			for (int i = 0; i < attrs.length; i++) {
				attrValue = cntxt.getAttributeValue(mappedKey, attrs[i]);
				if (attrValue == null)
					count++;
				sb.append(attrValue);
				if (i + 1 < attrs.length)
					sb.append(APS);
			}
			if (count < attrs.length)
				result = sb.toString();
		}
		return result;
	}

	public Context mark(String path, String association) throws ContextException {
		int firstAPS = association.indexOf(APS);
        String assoc = association;
		if (firstAPS <= 0) {
            if (association.toLowerCase().equals(Context.DA_IN)) {
                assoc = Context.CONTEXT_PARAMETER
                        + APS + Context.DA_IN + APS + APS + APS;
            } else if (association.toLowerCase().equals(Context.DA_OUT)) {
                assoc = Context.CONTEXT_PARAMETER
                        + APS + Context.DA_OUT + APS + APS + APS;
            } else if (association.toLowerCase().equals(Context.DA_INOUT)) {
                assoc = Context.CONTEXT_PARAMETER
                        + APS + Context.DA_INOUT + APS + APS + APS;
            } else {
                throw new ContextException(
                        "No attribute or metaattribute specified in: "
                                + association);
            }
        }

		String[] attributes = SorcerUtil.tokenize(assoc, APS);
		String values = assoc.substring(attributes[0].length() + 1);
        return addComponentAssociation(path, attributes[0], values);
	}

	public Context addComponentAssociation(String path, String attribute,
										   String attributeValue) throws ContextException {
		Hashtable values;
		// locate the context and context path for this key
		Object[] map = getContextMapping(path);
		ServiceContext cntxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];

		if (cntxt.isSingletonAttribute(attribute)) {
			values = (Hashtable) cntxt.metacontext.get(attribute);
			if (values == null) {
				// the creation of this hashtable was delayed until now
				values = new Hashtable();
				cntxt.metacontext.put(attribute, values);
			}
			values.put(mappedKey, attributeValue);
		} else if (cntxt.isMetaattribute(attribute))
			cntxt.addCompositeAssociation(mappedKey, attribute, attributeValue);
		else
			throw new ContextException("No attribute defined: \"" + attribute
					+ "\" in this context (name=\"" + cntxt.getName() + "\"");
		return this;
	}

	public Context addCompositeAssociation(String path, String metaattribute,
										   String metaattributeValue) throws ContextException {

		// locate the context and context path for this path
		Object[] map = getContextMapping(path);
		Context cntxt = (Context) map[0];
		String mappedKey = (String) map[1];

		if (!cntxt.isMetaattribute(metaattribute))
			throw new ContextException("No metaattribute defined: "
					+ metaattribute + " in context " + cntxt.getName());
		String[] attrs = SorcerUtil.tokenize(
				cntxt.getLocalMetapath(metaattribute), APS);
		String[] vals = SorcerUtil.tokenize(metaattributeValue, APS);
		if (attrs.length != vals.length)
			throw new ContextException("Invalid:  The metavalue of \""
					+ metaattributeValue + "\" for metaattribute \""
					+ metaattribute + APS + getLocalMetapath(metaattribute)
					+ "\" is invalid in this context (name=\""
					+ cntxt.getName() + "\"");
		for (int i = 0; i < attrs.length; i++)
			((ServiceContext) cntxt).addComponentAssociation(mappedKey,
					attrs[i], vals[i]);
		return this;
	}

	public List<String> markedPaths(String association) throws ContextException {
		String attr, value;
		Map values;
		if (association == null)
			return null;
		int index = association.indexOf(SorcerConstants.APS);
		if (index < 0)
			return null;

		attr = association.substring(0, index);
		value = association.substring(index + 1);
		if (!isAttribute(attr))
			throw new ContextException("No Attribute defined: " + attr);

		List<String> keys = new ArrayList<String>();
		if (isSingletonAttribute(attr)) {
			values = (Map)getMetacontext().get(attr);
			if (values != null) { // if there are no attributes setValue,
				// values==null;
				for (Object key : values.keySet()) {
					if (values.get(key).equals(value))
						keys.add((String) key);
				}
			}
		} else {
			// it is a metaattribute
			String metapath = getLocalMetapath(attr);
			if (metapath != null) {
				String[] attrs = SorcerUtil.tokenize(metapath,
						SorcerConstants.APS);
				String[] vals = SorcerUtil.tokenize(value, SorcerConstants.APS);
				if (attrs.length != vals.length)
					throw new ContextException("Invalid association: \""
							+ association + "\"  metaattribute \"" + attr
							+ "\" is defined with metapath =\"" + metapath
							+ "\"");
				Object[][] paths = new Object[attrs.length][];
				List<String> mps;
				int ii = -1;
				for (int i = 0; i < attrs.length; i++) {
					paths[i] = markedPaths(attrs[i] + SorcerConstants.APS + vals[i]).toArray();;
					if (paths[i] == null) {
						ii = -1;
						break; // i.e. no possible match
					}
					if (paths[i] != null && (ii < 0 || paths[i].length > paths[ii].length)) {
						ii = i;
					}
				}
				if (ii >= 0) {
					// The common paths across the paths[][] array are
					// matches. Said another way, the paths[][] array
					// contains all the paths that match attributes in the
					// metapath. paths[0][] are the matches for the first
					// element of the metapath, paths[1][] for the next,
					// etc. Therefore, the matches that are common for
					// each element of the metapath are the ones in which
					// we have interest.
					String candidate;
					int match, thisMatch;
					// go through each element of one with most matches
					for (int i = 0; i < paths[ii].length; i++) {
						candidate = (String) paths[ii][i];
						// now look for paths.length-1 matches...
						match = 0;
						for (int j = 0; j < paths.length; j++) {
							if (j == ii)
								continue;
							thisMatch = 0;
							for (int k = 0; k < paths[j].length; k++)
								if (candidate.equals(paths[j][k])) {
									match++;
									thisMatch++;
									break;
								}
							if (thisMatch == 0)
								break; // no possible match for this candidate
						}
						// System.out.println("candidate="+candidate+"
						// match="+match+" required maches="+(paths.length-1));
						if (match == paths.length - 1)
							keys.add(candidate);
					}
				}
			}
		}
		// above we just checked the top-level context; next, check
		// all the top-level LINKED contexts (which in turn will check
		// all their top-level linked contexts, etc.)
		List<String> paths = localLinkPaths();
		List<String> keysInLinks;
		ContextLink link;
		for (String linkPath : paths) {
			link = (ContextLink) get(linkPath);
			ServiceContext lcxt = (ServiceContext) getLinkedContext(link);
			keysInLinks = lcxt.markedPaths(association);
			if (keysInLinks != null)
				for (String key : keysInLinks) {
					keys.add(linkPath + SorcerConstants.CPS
							+ key);
				}
		}
		return keys;
	}

	public void removeAttributeValue(String path, String attributeValue)
			throws ContextException {
		String attr;
		// accept also metaassociation
		if (attributeValue.indexOf(APS) > 0)
			attr = SorcerUtil.firstToken(attributeValue, APS);
		else
			attr = attributeValue;

		// locate the context and context path for this key
		Object[] map = null;
		map = getContextMapping(path);

		ServiceContext cntxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];

		if (cntxt.isSingletonAttribute(attr)) {
			Hashtable metavalues = (Hashtable) cntxt.getMetacontext().get(attr);

			if (metavalues == null)
				return;
			metavalues.remove(mappedKey);
			// remove Hashtable if it is now empty
			if (metavalues.size() == 0)
				metacontext.remove(attr);
		} else if (cntxt.isMetaattribute(attr)) {
			String[] attrs = SorcerUtil.tokenize(cntxt.getLocalMetapath(attr),
					APS);
			for (int i = 0; i < attrs.length; i++)
				cntxt.removeAttributeValue(mappedKey, attrs[i]);
		} else
			throw new ContextException("No attribute defined: " + attr
					+ " in context " + cntxt.getName());
	}

	public String getLocalMetapath(String metaattribute)
			throws ContextException {
		// return the metapath (attribute-name n-tuple) equivalent to
		// this metaattribute; format is a String with attributes
		// separated by the CMPS (context metapath separator)
		if (isMetaattribute(metaattribute))
			return (String) getDataAttributeMap().get(metaattribute);
		else
			return null;
	}

	public boolean isValid(Signature signature) throws ContextException {
		Provider provider = null;
		try {
			provider = getProvider();
		} catch (SignatureException e) {
			throw new ContextException(e);
		}
		if (provider != null)
			return ((ServiceProvider) provider).isContextValid(this, signature);
		else {
			return true;
		}
	}

	public List<String> paths(String regex) throws ContextException {
		Iterator e = getPaths().iterator();
		List<String> list = new ArrayList<String>();
		Pattern p = Pattern.compile(regex);
		String path;
		while (e.hasNext()) {
			path = (String) e.next();
			if (p.matcher(path).matches())
				list.add(path);
		}
		return list;
	}

	public List<String> getPaths() throws ContextException {
		ArrayList<String> paths = new ArrayList<String>();
		Iterator i = keyIterator();
		String key, path;
		ContextLink link;
		Context subcntxt;
		while (i.hasNext()) {
			key = (String) i.next();
			if (get(key) instanceof ContextLink) {
				// follow link, add paths
				link = (ContextLink) get(key);
				try {
					subcntxt = getLinkedContext(link)
							.getContext(link.getOffset());
				} catch (RemoteException ex) {
					throw new ContextException(ex);
				}
				// getDirectionalSubcontext cuts above, which is what we want
				Iterator<String> el = subcntxt.getPaths().iterator();
				while (el.hasNext()) {
					path = (String) el.next();
					paths.add(key + CPS +path);
				}
			}
			paths.add(key);
		}
		Collections.sort(paths);
		return paths;
	}

	public Enumeration contextValues() throws ContextException {
		Iterator e = getPaths().iterator();
		Vector vec = new Vector();
		while (e.hasNext())
			try {
				vec.addElement(getValue((String) e.next()));
			} catch (Exception ex) {
				throw new ContextException(ex);
			}
		return vec.elements();
	}

	public List<String> localLinkPaths() throws ContextException {
		List<String> keys = new ArrayList<String>();
		Iterator i = keyIterator();
		String key;

		while (i.hasNext()) {
			key = (String) i.next();
			if (get(key) instanceof ContextLink)
				keys.add(key);
		}
		SorcerUtil.bubbleSort(keys);
		return keys;
	}

	/**
	 * Returns a list of all paths marked as data input.
	 *
	 * @return list of all paths marked as input
	 * @throws ContextException
	 */
	public List<String> getInPaths() throws ContextException {
		return Contexts.getInPaths(this);
	}

	public List<String> getAllInPaths() throws ContextException {
		return Contexts.getAllInPaths(this);
	}

	public String getValClass(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		return vc;
	}

	public boolean isString(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		return vc.equals(""+ String.class);
	}

	public boolean isInt(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ int.class) || vc.equals(""+ Integer.class);
		return is;
	}
	public boolean isShort(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ short.class) || vc.equals(""+ Short.class);
		return is;
	}

	public boolean isLong(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ long.class) || vc.equals(""+ Long.class);
		return is;
	}

	public boolean isFloat(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ float.class) || vc.equals(""+ Float.class);
		return is;
	}

	public boolean isDouble(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ double.class) || vc.equals(""+ Double.class);
		return is;
	}

	public boolean isByte(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ byte.class) || vc.equals(""+ Byte.class);
		return is;
	}

	public boolean isBoolean(String path) throws ContextException {
		String vc = (String) ((Hashtable)getMetacontext().get("vc")).get(path);
		boolean is = vc.equals(""+ boolean.class) || vc.equals(""+ Boolean.class);
		return is;
	}

	public EntList getPars() {
		EntList pl = new EntList();
		Iterator<Map.Entry<String, T>> i = entryIterator();
		Map.Entry<String, T> entry;
		while (i.hasNext()) {
			entry = i.next();
			if (entry.getValue() instanceof Proc) {
				pl.add((Proc)entry.getValue());
			}
		}
		return pl;
	}

	/**
	 * Returns a list of all paths marked as data output.
	 *
	 * @return list of all paths marked as data output
	 * @throws ContextException
	 */
	public List<String> getOutPaths() throws ContextException {
		return Contexts.getOutPaths(this);
	}

	/**
	 * Returns a list of input context values marked as data input.
	 *
	 * @return a list of input values of this context
	 * @throws ContextException
	 * @throws ContextException
	 */
	public List<Object> getInValues() throws ContextException {
		List<?> inpaths = Contexts.getInPaths(this);
		List<Object> list = new ArrayList<Object>(inpaths.size());
		for (Object path : inpaths)
			try {
				list.add(getValue((String) path));
			} catch (Exception e) {
				throw new ContextException(e);
			}
		return list;
	}

	public List<Object> getAllInValues() throws ContextException {
		List<?> inpaths = Contexts.getAllInPaths(this);
		List<Object> list = new ArrayList<Object>(inpaths.size());
		for (Object path : inpaths)
			try {
				list.add(getValue((String) path));
			} catch (Exception e) {
				throw new ContextException(e);
			}
		return list;
	}

	/**
	 * Returns a list of output context values marked as data input.
	 *
	 * @throws ContextException
	 *
	 * @return list of output values of this context
	 * @throws ContextException
	 */
	public List<Object> getOutValues() throws ContextException {
		List<?> outpaths = Contexts.getOutPaths(this);
		List<Object> list = new ArrayList<Object>(outpaths.size());
		for (Object path : outpaths)
			try {
				list.add(getValue((String) path));
			} catch (Exception e) {
				throw new ContextException(e);
			}

		return list;
	}

	public Enumeration<String> linkPaths() throws ContextException {
		// returns paths to all ContextLink objects
		Vector<String> keys = new Vector<String>();
		Iterator<String> i = keyIterator();
		String key, path;
		ContextLink link;
		Context subcntxt = null;

		while (i.hasNext()) {
			key = i.next();
			if (get(key) instanceof ContextLink) {
				keys.addElement(key);
				link = (ContextLink) get(key);
				// get subcontext for recursion
				try {
					subcntxt = getLinkedContext(link)
							.getContext(link.getOffset());
				} catch (RemoteException ex) {
					throw new ContextException(ex);
				}
				// getDirectionalSubcontext cuts above, which is what we want
				Enumeration<?> el = subcntxt.linkPaths();
				while (el.hasMoreElements()) {
					path = (String) el.nextElement();
					keys.addElement(key + CPS + path);
				}
			} else {
				keys.addElement(key);
			}
		}// end of instance of ContextLink
		SorcerUtil.bubbleSort(keys);
		return keys.elements();
	}

	public Enumeration<Link> links() throws ContextException {
		Enumeration<String> e = linkPaths();
		String path;
		Vector<Link> links = new Vector<Link>();
		while (e.hasMoreElements()) {
			path = (String) e.nextElement();
			links.addElement(getLink(path));
		}
		return links.elements();
	}

	public List<Link> localLinks() throws ContextException {
		List<String> paths = localLinkPaths();
		List<Link> links = new ArrayList<Link>();
		for (String path : paths) {
			links.add(getLink(path));
		}
		return links;
	}

	public Context execSignature(Signature sig, Arg... items) throws MogramException {
		if (sig.getReturnPath() == null)
			throw new MogramException("No signature return path defined!");
		ReturnPath rp = (ReturnPath) sig.getReturnPath();

		if (rp.getPath() == null) {
			rp.path = sig.getName();
		}
		Path[] ips = rp.getInSigPaths();
		Path[] ops = rp.getOutSigPaths();
		Context incxt = null;
		if (rp.getDataContext() != null) {
			incxt = rp.getDataContext();
			incxt.setScope(this);
		}
		if (incxt != null) {
			if (ips != null && ips.length > 0) {
				incxt.setScope(this.getEvaluatedSubcontext(ips, items));
			}
		} else {
			incxt = this;
			if (ips != null && ips.length > 0) {
				incxt = this.getEvaluatedSubcontext(ips, items);
			}
		}
		incxt.setReturnPath(rp);
		String returnPath = rp.getPath();
		Context outcxt, resultContext = null;
		try {
			// define output context here
			sig.setReturnPath((SignatureReturnPath)null);
			Task sTask = task(sig, incxt);
			sTask.setAccess(sig.getAccessType());
			outcxt = task(sig, incxt).exert().getContext();
			// restore return path
			sig.setReturnPath(rp);
		} catch (Exception e) {
			throw new MogramException(e);
		}
		resultContext = outcxt;
		if (ops != null && ops.length > 0) {
			Context returnContext = outcxt.getDirectionalSubcontext(ops);
			// make sure the result is returned correctly
			resultContext.putValue(returnPath, returnContext);
			this.appendInout(returnContext);
			this.setIsChanged(true);
		} else {
			this.appendInout(outcxt);
			this.setIsChanged(true);
		}
		return resultContext;
	}

	public ServiceContext getSubcontext(Path[] paths) throws ContextException {
		ServiceContext subcntxt = getDirectionalSubcontext(paths);
		for (Path path : paths) {
			try {
				subcntxt.put(path.path, get(path.path));
			} catch(Exception e) {

			}
		}
		return subcntxt;
	}

	public ServiceContext getSubcontext() throws ContextException {
		ServiceContext subcntxt = new PositionalContext();
		subcntxt.setSubject(subjectPath, subjectValue);
		subcntxt.setName(getName() + " subcontext");
		subcntxt.setDomainId(getDomainId());
		subcntxt.setSubdomainId(getSubdomainId());
		return subcntxt;
	}

	public ServiceContext getDirectionalSubcontext(Path[] paths) throws ContextException {
		// bare-bones subcontext
		ServiceContext subcntxt = getSubcontext();
		List<String> inpaths = getInPaths();
		List<String> outpaths = getOutPaths();
		if  (paths != null && paths.length > 0) {
			for (Path path : paths) {
				if (inpaths.contains(path.path))
					subcntxt.putInValue(path.path, get(path.path));
				else if (outpaths.contains(path.path))
					subcntxt.putOutValue(path.path, get(path.path));
				else
					subcntxt.putValue(path.path, get(path.path));
			}
		}
		return subcntxt;
	}

	public ServiceContext getEvaluatedSubcontext(Path[] paths, Arg[] items) throws ContextException {
		ServiceContext subcntxt = getSubcontext();
		List<String> inpaths = getInPaths();
		List<String> outpaths = getOutPaths();

		for (Path path : paths) {
			// tag the context with provided info
			if(path.info != null) {
				subcntxt.putValue(path.path, getValue(path.path), path.info.toString());
			} else if (inpaths.contains(path.path))
				subcntxt.putInValue(path.path, getValue(path.path, items));
			else if (outpaths.contains(path))
				subcntxt.putInoutValue(path.path, getValue(path.path, items));
			else
				subcntxt.putValue(path.path, getValue(path.path, items));
		}
		// annotate paths as provided by paths
		for (Path p : paths) {
			if (p.info != null) {
				subcntxt.mark(p.path, (String)p.info);
			}
		}
		return subcntxt;
	}

	public ServiceContext getMergedSubcontext(ServiceContext intial, List<Path> paths, Arg... args)
			throws ContextException {
		ServiceContext subcntxt = null;
		if (intial != null) {
			subcntxt = intial;
		} else {
			subcntxt = getSubcontext();
		}
		subcntxt.setModeling(true);
		Object val = null;
		for (Arg arg : paths) {
			String path = arg.getName();
			val = getValue(path, args);
			if (val instanceof Context) {
				subcntxt.append((Context) val);
			} else if (val instanceof Entry) {
				Object v = ((Entry)val).value();
				subcntxt.putValue(path, v);
				if (path != ((Entry)val).getName())
					subcntxt.putValue(((Entry)val).getName(), v);
			} else {
				List<String> inpaths = getInPaths();
				List<String> outpaths = getOutPaths();
				if (inpaths.contains(path))
					subcntxt.putInValue(path, val);
				else if (outpaths.contains(path))
					subcntxt.putOutValue(path, val);
				else
					subcntxt.putValue(path, val);
			}
		}
		return subcntxt;
	}

	public ServiceContext getContext(String path) throws ContextException, RemoteException {
		ServiceContext subcntxt = this.getSubcontext();
		return (ServiceContext)subcntxt.appendContext(this, path);
	}

	public Context getTaskContext(String path) throws ContextException {
		// needed for ContextFilter
		return null;
	}

	// TODO in/out/inout marking as defined in the connector
	public Context updateContextWith(Context context) throws ContextException {
		boolean isRedundant = false;
		if (context instanceof MapContext) {
			isRedundant = ((MapContext) context).isRedundant;
		}
		if (context != null) {
			Iterator it = ((ServiceContext)context).entryIterator();
			while (it.hasNext()) {
				Map.Entry e = (Map.Entry) it.next();
				putInValue((String) e.getKey(), asis((String) e.getValue()));
				if (!isRedundant) {
					removePath((String) e.getValue());
				}
			}
		}
		return this;
	}

	public Context updateEntries(Domain context) throws ContextException {
		if (context != null) {
			List<String> inpaths = ((ServiceContext) context).getInPaths();
			List<String> outpaths = ((ServiceContext) context).getOutPaths();
			Iterator it = ((ServiceContext)context).entryIterator();
			while (it.hasNext()) {
				Map.Entry<String, Object> entry = (Map.Entry) it.next();
				String path = entry.getKey();
				Object val = entry.getValue();
				if (containsPath(path)) {
					if (inpaths.contains(path))
						putInValue(path, (T) val);
					else if (outpaths.contains(path))
						putOutValue(path, (T) val);
					else
						putValue(path, (T) val);
				}
			}
			if (containsPath(Condition._closure_))
				remove(Condition._closure_);
		}
		return this;
	}
	/* (non-Javadoc)
	 * @see sorcer.service.Context#append(sorcer.service.Context)
	 */
	public Context append(Context context) throws ContextException {
		if (context != null && this != context) {
			putAll((ServiceContext)context);
			// annotate as in the argument context
			List<String> inpaths = ((ServiceContext) context).getInPaths();
			List<String> outpaths = ((ServiceContext) context).getOutPaths();
			for (String p : inpaths) {
				Contexts.markIn(this, p);
//				tag(p, "cp|in||");
			}
			for (String p : outpaths) {
				Contexts.markOut(this, p);
//				tag(p, "cp|out||");
			}
			if (containsPath(Condition._closure_))
				remove(Condition._closure_);
		}
//		if (((ServiceContext)scope).containsPath(Condition._closure_)) {
//			scope.remove(Condition._closure_);
//		}
		return this;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Contexter#appendContext(sorcer.service.Context)
	 */
	@Override
	public Context<T> appendContext(Context<T> context) throws ContextException,
			RemoteException {
		// get the whole context, with the context root name as the
		// path prefix
		String key;
		List<String> paths = new ArrayList<String>();
		int index;
		// pick off all top-level nodes to append
		Iterator e = context.getPaths().iterator();
		while (e.hasNext()) {
			key = (String) e.next();
			index = key.indexOf(CPS);
			if (index != -1)
				key = key.substring(0, index);
			if (!paths.contains(key))
				paths.add(key);
		}
		for (String path : paths) {
			appendContext(context, path, true);
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Contexter#appendContext(sorcer.service.Context, java.lang.String)
	 */
	public Context appendContext(Context cntxt, String path)
			throws ContextException, RemoteException {
		return appendContext(cntxt, path, false);
	}

	public Context appendContext(Context cntxt, String path,
								 boolean prefixContextName) throws ContextException,  RemoteException {
		// appendContext snips the context (passed in as the first
		// argument) BEFORE the requested node and returns it appended
		// to the context object. Said another way: if the context, ctx,
		// has the following paths
		//
		// a/b
		// a/b/c
		// d/e
		//
		// appendSubcontext(ctx, "a/b") returns context with keys
		// b
		// b/c
		//
		// appendSubcontext(ctx, "a") returns context with keys
		// a/b
		// a/b/c

		// path should not have a trailing slash

		String newKey, oldKey, cntxtKey;
		int index;
		Iterator e1;
		if (path == null)
			throw new ContextException("null path");
		if (path.equals("")) {
			// append entire context
			return appendContext(cntxt);
		}

		Object[] map = null;
		map = cntxt.getContextMapping(path);
		ServiceContext mappedCntxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];
		// System.out.println("path="+path);
		// System.out.println("mappedKey="+mappedKey);
		// System.out.println("orig context name="+cntxt.getName());
		// System.out.println("mapped context name="+mappedCntxt.getName());

		int len = mappedKey.length();
		String prefix;
		Iterator<String> e = mappedCntxt.keyIterator();
		while (e.hasNext()) {
			cntxtKey = e.next();
			if (cntxtKey.startsWith(mappedKey)) {
				// we could still have the case key="a/b"
				// cntxtKey="a/bc", which should fail, but
				// cntxtKey="a/b" or cntxtKey="a/b/*" passes.
				// This next conditional should do the trick:
				if (cntxtKey.length() == len
						|| cntxtKey.indexOf(CPS, len) == len) {
					index = mappedKey.lastIndexOf(CPS, len - 1);
					if (index > 0)
						newKey = cntxtKey.substring(index + 1);
					else
						newKey = cntxtKey;
					oldKey = cntxtKey;
					// should we test for clobber protection?
					// i.e. these new keys could be dropped on old ones
					if (prefixContextName) {
						prefix = "";
						if (mappedCntxt.getSubjectPath().length() > 0)
							prefix = mappedCntxt.getSubjectPath() + CPS;
						putValue(prefix + newKey, mappedCntxt.get(oldKey));
					} else
						putValue(newKey, mappedCntxt.get(oldKey));
				}
			}
		}
		// replicate subcontext attributes and metaattributes
		Map table, attrTable;
		attrTable = ((ServiceContext) mappedCntxt).metacontext;
		// note the metacontext contains only singleton attributes
		// AND the SORCER.CONTEXT_ATTRIBUTES dataTable
		e = attrTable.keySet().iterator();
		String attr, val, metapath;
		while (e.hasNext()) {
			attr = (String) e.next();
			// make sure we don't enumerate over the CONTEXT_ATTRIBUTES
			if (attr.equals(CONTEXT_ATTRIBUTES))
				continue;
			table = (Hashtable) attrTable.get(attr);
			e1 = table.keySet().iterator();
			while (e1.hasNext()) {
				cntxtKey = (String) e1.next();
				if (cntxtKey.startsWith(mappedKey)) {
					if (cntxtKey.length() == len
							|| cntxtKey.indexOf(CPS, len) == len) {
						index = mappedKey.lastIndexOf(CPS, len - 1);
						if (index > 0)
							newKey = cntxtKey.substring(index + 1);
						else
							newKey = cntxtKey;
						oldKey = cntxtKey;
						val = (String) table.get(oldKey);
						if (!isSingletonAttribute(attr))
							setComponentAttribute(attr);
						if (prefixContextName)
							addComponentAssociation(mappedCntxt.getName() + CPS
									+ newKey, attr, val);
						else
							addComponentAssociation(newKey, attr, val);
					}
				}
			}
		}

		// now all attributes are setValue, and metaattributes are setValue
		// implicitly IF the metaattribute definitions are setValue in the
		// new context. So, next we setValue the definitions, or at least
		// try...

		String metapath_target, metapath_source;
		// enumerate over local metaattributes
		e = mappedCntxt.getDataAttributeMap().keySet().iterator();
		while (e.hasNext()) {
			attr = (String) e.next();
			if (!mappedCntxt.isLocalMetaattribute(attr))
				continue;
			// is this also an attribute in the current context?
			if (isSingletonAttribute(attr)) {
//				logger.info("The attribute \""
//						+ attr
//						+ "\" has conflicting definitions; it is a metaattribute in the source context and a singleton attribute in the target context.  Please correct before performing this operation");
//				logger.info("Src metacontext="
//						+ mappedCntxt.metacontext);
//				logger.info("this metacontext=" + metacontext);
				throw new ContextException("The attribute \"" + attr
						+ "\" has conflicting definitions;");// it
				// is a metaattribute in the source context and a singleton
				// attribute in the target context.
				// Please correct before performing this operation");
			}
			// is this also a metaattribute in the current context?
			if (isMetaattribute(attr)) {
				// check to see the definitions are the same
				metapath_source = (String) mappedCntxt
						.getDataAttributeMap().get(attr);
				metapath_target = (String) getDataAttributeMap().get(attr);
				if (!metapath_target.equals(metapath_source))
					throw new ContextException("The metaattribute \"" + attr
							+ "\" has conflicting definitions");// in
				// the source and target contexts; in the source
				// context, it has metapath = \""+metapath_source+"\",
				// while in the target context it
				// has metapath = \""+metapath_target+"\".
				// Please correct befe performing this operation.");
			}
			metapath = (String) mappedCntxt
					.getDataAttributeMap().get(attr);
			setCompositeAttribute(attr + APS + metapath);
		}
		return this;
	}

	public void removePath(String path) throws ContextException {
		// locate the context and context path for this key
		Object[] map = getContextMapping(path);
		ServiceContext cxt = (ServiceContext) map[0];
		String mappedKey = (String) map[1];
		cxt.remove(mappedKey);
		// Remove the path if it exists in metaAttribute also.
		Iterator<String> e = cxt.metacontext.keySet().iterator();
		String key;
		Map attributes;
		while (e.hasNext()) {
			key = (String) e.next();
			if (key.startsWith(PRIVATE) && key.endsWith(PRIVATE))
				continue;
			attributes = (Hashtable) cxt.metacontext.get(key);
			if (attributes.containsKey(mappedKey))
				attributes.remove(mappedKey);
		}
	}

	public String toString(String cr, StringBuilder sb, boolean withMetacontext) throws ContextException {
		sb.append(subjectPath.length() == 0 ? "" : "\n  subject: "
				+ subjectPath + ":" + subjectValue + cr);

		Object val;
		int count = 0;
		for (String path : getPaths()) {
			val = get(path);
			if (!(val instanceof ContextLink)) {
				if (count >= 1)
					sb.append(cr);
				sb.append("  " + path).append(" = ");
			}
			// if (val instanceof ContextLink) {
			// sb.append(val.toString() + " ");
			// }
			try {
				if (val instanceof Proc)
					val = "proc: " + ((Proc)val).getName();
				else
//					val = getValue(path);
					val = asis(path);
			} catch (Exception ex) {
				sb.append("\nUnable to retrieve eval: " + ex.getMessage());
				ex.printStackTrace();
				val = Context.none;;
//				continue;
			}
			// if (val == null)
			// sb.append("null");
			if (val != null) {
				if (val.getClass().isArray())
					sb.append(SorcerUtil.arrayToString(val));
				else if (val instanceof ContextNode
						&& ((ContextNode) val).isURL()) {
					URL url;
					try {
						url = ((ContextNode) val).getURL();
						sb.append("<a href=").append(url).append(">")
								.append(url).append("</a>");
					} catch (MalformedURLException e2) {
						e2.printStackTrace();
					} catch (ContextNodeException e2) {
						e2.printStackTrace();
					}
				} else if (val instanceof Exertion) {
					sb.append(((ServiceExertion) val).info());
				} else
					sb.append(val.toString());
			}
			count++;
		}
		if (returnPath != null) {
			sb.append("\n  return/path = " + returnPath);
		}
		if (returnJobPath != null) {
			sb.append("\n  return/job/path = " + returnJobPath);
		}
		if (withMetacontext)
			sb.append("\n metacontext: " + metacontext);

		if (scope != null)
			sb.append("\n scope: " + ((ServiceContext) scope).keySet());
		// sb.append(cr);
		// sb.append(cr);
		if (cr.equals("<br>"))
			sb.append("</html>");
		return sb.toString();
	}

	public String toStringComplete(String cr, StringBuffer sb) {
		sb.append("Domain:").append(domainId);
		sb.append(" SubDomain:" + subdomainId);
		sb.append(" ID:" + mogramId);
		sb.append("\nPaths: \n");
		Iterator<String> e = null;
		try {
			e = getPaths().iterator(); // sorted enumeration
		} catch (ContextException ex) {
			sb.append("ERROR: ContextException thrown: " + ex.getMessage());
			return sb.toString();
		}
		List<String> e1;
		String path;
		Object val;
		while (e.hasNext()) {
			path = (String) e.next();
			// System.out.print(path);
			sb.append(path).append(" = ");
			try {
				// System.out.println(" = "+getValue(path));
				val = getValue(path);
			} catch (Exception ex) {
				sb.append("\nUnable to retrieve eval: " + ex.getMessage());
				ex.printStackTrace();
				continue;
			}
			if (val == null)
				sb.append("null");
			else if (val.getClass().isArray())
				sb.append(SorcerUtil.arrayToString(val));
			else
				sb.append(val.toString());
			// report attributes
			try {
				e1 = Contexts.getSimpleAssociations(this, path);
			} catch (ContextException ex) {
				sb.append("Unable to retrieve associations: " + ex.getMessage());
				continue;
			}
			if (e1 != null && e1.size() > 0) {
				sb.append(" {");

				sb.append("}");
			}
			try {
				e1 = metaassociations(path);
			} catch (ContextException ex) {
				sb.append("Unable to retrieve meta-associations: "
						+ ex.getMessage());
				continue;
			}
			if (e1 != null && e1.size() > 0) {
				sb.append(" {");
				sb.append(e1);
				sb.append("}");
			}
			sb.append(cr);
		}
		return sb.toString();
	}

	public String toString() {
		try {
			return toString(false, false);
		} catch (ContextException e) {
			e.printStackTrace();
		}
		return null;
	}

	public String toString(boolean isHTML) throws ContextException {
		if (isHTML)
			return toString(isHTML, false);
		else
			return toString(isHTML, true);
	}

	public String toString(boolean isHTML, boolean withMetacontext) throws ContextException {
		String cr; // Carriage return
		StringBuilder sb; // String buffer
		if (isHTML) {
			cr = "<br>";
			// sb = new StringBuilder("<html>\nContext name: ");
			sb = new StringBuilder("<html>\n");
		} else {
			cr = "\n";
			sb = new StringBuilder(name != null ? "Context name: " + name
					+ "\n" : "");
			// sb = new StringBuilder();
		}
		// sb.append(name).append("\n");
		return toString(cr, sb, withMetacontext);
	}

	public Map<String, Map<String, String>> getMetacontext() {
		return metacontext;
	}

	public void connect(String outPath, String inPath, Context inContext)
			throws ContextException {
		Contexts.markIn(inContext, inPath);
		Contexts.markOut(this, outPath);
		map(outPath, inPath, inContext);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see sorcer.service.Context#pipe(java.lang.String, java.lang.String,
	 * sorcer.service.Context)
	 */
	@Override
	public void pipe(String inPath, String outPath, Context outContext)
			throws ContextException {
		Contexts.markInPipe(this, inPath);
		Contexts.markOutPipe(outContext, outPath);
		map(outPath, inPath, outContext);

	}

	public T putInValue(String path, T value) throws ContextException {
		putValue(path, value);
		Contexts.markIn(this, path);
		return value;
	}

	public T putOutValue(String path, T value)
			throws ContextException {
		putValue(path, value);
		Contexts.markOut(this, path);
		return value;
	}

	public Object putErrValue(String path, T value)
			throws ContextException {
		putValue(path, value);
		Contexts.markOut(this, path);
		return value;
	}

	public Object[] getArgs() throws ContextException {
		if (argsPath == null)
			return null;
		else
			return (Object[])get(argsPath);
	}

	public ServiceContext setArgs(Object... args) throws ContextException {
		if (argsPath == null)
			argsPath = Context.PARAMETER_VALUES;
		putInValue(argsPath, (T) args);
		return this;
	}

	public String getArgsPath() {
		return argsPath;
	}

	public ServiceContext setArgsPath(String targetPath)
			throws ContextException {
		argsPath = targetPath;
		return this;
	}

	public Class[] getParameterTypes() throws ContextException {
		if (parameterTypesPath != null)
			return (Class[]) getValue(parameterTypesPath);
		else
			return null;
	}

	public ServiceContext setParameterTypes(Class... types) throws ContextException {
		if (parameterTypesPath == null)
			parameterTypesPath = Context.PARAMETER_TYPES;
		putValue(parameterTypesPath, (T) types);
		return this;
	}

	public String getParameterTypesPath() {
		return parameterTypesPath;
	}

	public ServiceContext setParameterTypesPath(String targetPath)
			throws ContextException {
		parameterTypesPath = targetPath;
		return this;
	}

	public ReturnPath getReturnPath() {
		return returnPath;
	}

	public ServiceContext setReturnPath() throws ContextException {
		this.returnPath = new ReturnPath();
		return this;
	}

	public ServiceContext setReturnPath(String path) throws ContextException {
		this.returnPath = new ReturnPath(path);
		return this;
	}

	public ServiceContext setReturnPath(SignatureReturnPath returnPath) {
		this.returnPath = (ReturnPath)returnPath;
		return this;
	}

	public void setReturnValue(Object value) throws ContextException {
		if (returnPath == null)
			returnPath = new ReturnPath(Context.RETURN);

		if (returnPath.path != null) {
			if (value == null)
				putValue(returnPath.path, (T)none);
			else
				putValue(returnPath.path, value);

			if (returnPath.direction == Direction.IN)
				Contexts.markIn(this, returnPath.path);
			else if (returnPath.direction == Direction.OUT)
				Contexts.markOut(this, returnPath.path);
			if (returnPath.direction == Direction.INOUT)
				Contexts.markInout(this, returnPath.path);
		}
	}

	public ReturnPath getReturnJobPath() {
		return returnJobPath;
	}

	public ServiceContext setReturnJobPath() throws ContextException {
		this.returnJobPath = new ReturnPath();
		return this;
	}

	public ServiceContext setReturnJobPath(String path) throws ContextException {
		this.returnJobPath = new ReturnPath(path);
		return this;
	}

	public ServiceContext setReturnJobPath(ReturnPath returnPath)
			throws ContextException {
		this.returnJobPath = returnPath;
		return this;
	}

	public T setReturnJobValue(T value) throws ContextException {
		if (returnJobPath == null)
			returnJobPath = new ReturnPath(Context.RETURN);

		if (value == null)
			putValue(returnJobPath.path, (T)none);
		else
			putValue(returnJobPath.path, value);

		if (returnJobPath.direction == Direction.IN)
			Contexts.markIn(this, returnJobPath.path);
		else if (returnJobPath.direction == Direction.OUT)
			Contexts.markOut(this, returnJobPath.path);
		if (returnJobPath.direction == Direction.INOUT)
			Contexts.markInout(this, returnJobPath.path);

		return value;
	}

	public T putInoutValue(String path, T value)
			throws ContextException {
		putValue(path, value);
		Contexts.markInout(this, path);
		return value;
	}

	public Context appendInout(Context context) throws ContextException {
		Iterator it = ((ServiceContext)context).entryIterator();
		while (it.hasNext()) {
			Map.Entry<String, Object> pairs = (Map.Entry) it.next();
			String path = pairs.getKey();
			if (data.containsKey(path) && data.get(path) instanceof Evaluation) {
				if (scope == null)
					scope = new ServiceContext();
				((ServiceContext)scope).putInoutValue(pairs.getKey(), pairs.getValue());
			} else {
				putInoutValue(pairs.getKey(), (T) pairs.getValue());
			}
		}
		return this;
	}

	public T putInValue(String path, T value, String association)
			throws ContextException {
		putValue(path, value);
		Contexts.markIn(this, path);
		mark(path, association);
		return value;
	}

	public T putOutValue(String path, T value, String association)
			throws ContextException {
		putValue(path, value);
		Contexts.markOut(this, path);
		mark(path, association);
		return value;
	}

	public T putInoutValue(String path, T value, String association)
			throws ContextException {
		putValue(path, value);
		Contexts.markInout(this, path);
		mark(path, association);
		return value;
	}

	public Context setIn(String path) throws ContextException {
		return Contexts.markIn(this, path);
	}

	public Context setOut(String path) throws ContextException {
		return Contexts.markOut(this, path);
	}

	public Context setInout(String path) throws ContextException {
		return Contexts.markInout(this, path);
	}

	public void removePathWithoutDeleted(String path) {
		this.remove(path);
		// Remove the path if it exists in metaAttribute also.
		Iterator<Map<String, String>> i = metacontext.values().iterator();
		while (i.hasNext()) {
			Map<String, String> attributeHash = metacontext.get(i.next());
			if (attributeHash.containsKey(path))
				attributeHash.remove(path);
		}
	}

	public String getTitle() {
		return name + ", " + (domainName == null ? "" : domainName + ", ")
				+ (subdomainName == null ? "" : subdomainName);
	}

	public boolean isLinked() {
		Iterator i = entryIterator();
		while (i.hasNext()) {
			Map.Entry e = (Map.Entry)i.next();
			if (e.getValue() instanceof ContextLink)
				return true;
		}
		return false;
	}

	public boolean isLinkedContext(Object path) {
		Object result;
		// System.out.println("getValue: path = \""+path+"\"");
		result = data.get(path);
		if (result instanceof ContextLink) {
			return true;
		} else
			return false;
	}

	public boolean isLinkedPath(String path) throws ContextException {
		if (!(getValue(path) instanceof ContextLink))
			return false;
		Object result[] = getContextMapping(path);
		if (result[0] == null)
			return false;

		return true;
	}

	protected Context getLinkedContext(ContextLink link)
			throws ContextException {
		return link.getContext();
	}

	public String getPath(Object obj) throws ContextException {
		Iterator e = keyIterator();
		String key;
		Object tmp = null;
		while (e.hasNext()) {
			key = (String) e.next();
			try {
				tmp = getValue(key);
			} catch (Exception ex) {
				throw new ContextException(ex);
			}
			if (tmp == obj)
				return key;
		}
		return null;
	}

	public List<String> localSimpleAttributes() {
		Iterator i = getDataAttributeMap().keySet().iterator();
		List attributes = new ArrayList<String>();
		String key;
		while (i.hasNext()) {
			key = (String) i.next();
			if (isLocalSingletonAttribute(key)) {
				attributes.add(key);
			}
		}
		return attributes;
	}

	public List<String> simpleAttributes() throws ContextException {
		Enumeration e = links();
		Iterator<String> i;
		ContextLink link;
		ServiceContext linkedCntxt;
		List<String> attrs = new ArrayList<String>();
		String attr;

		// get local singleton attributes
		attrs.addAll(localSimpleAttributes());
		while (e.hasMoreElements()) {
			link = (ContextLink) e.nextElement();
			linkedCntxt = (ServiceContext) link.getContext();
			i = linkedCntxt.getDataAttributeMap().keySet().iterator();
			while (i.hasNext()) {
				attr = i.next();
				if (!linkedCntxt.isLocalSingletonAttribute(attr))
					continue;
				if (!attrs.contains(attr))
					// this probably doesn't work as I would like
					attrs.add(attr);
			}
		}
		return attrs;
	}

	public List<String> localCompositeAttributes() {
		Iterator<String> i = getDataAttributeMap().keySet().iterator();
		List<String> attributes = new ArrayList<String>();
		String key;
		while (i.hasNext()) {
			key = i.next();
			if (isLocalMetaattribute(key)) {
				attributes.add(key);
			}
		}
		return attributes;
	}

	public List<String>  compositeAttributes() throws ContextException {
		Enumeration e = links();
		List<String> e0;
		Iterator<String> e1;
		ContextLink link;
		ServiceContext linkedCntxt;
		Vector attrs = new Vector();
		String attr;

		// get local meta attributes
		e0 = localCompositeAttributes();
		attrs.addAll(e0);

		while (e.hasMoreElements()) {
			link = (ContextLink) e.nextElement();
			linkedCntxt = (ServiceContext) link.getContext();
			e1 = linkedCntxt.getDataAttributeMap().keySet().iterator();
			while (e1.hasNext()) {
				attr = e1.next();
				if (!linkedCntxt.isLocalMetaattribute(attr))
					continue;
				if (!attrs.contains(attr))
					// this probably doesn't work as
					// I would like
					attrs.addElement(attr);
			}
		}
		return attrs;
	}

	public List<String> getAttributes() throws ContextException {
		List<String> attrs = new ArrayList<String>();
		attrs.addAll(simpleAttributes());
		attrs.addAll(compositeAttributes());
		return attrs;
	}

	public List<String> getAttributes(String path) throws ContextException {
		List<String> atts = new ArrayList<String>();
		List<String> e = getAttributes();
		for (String att : getAttributes()) {
			if (getAttributeValue(path, att) != null)
				atts.add(att);
		}
		return atts;
	}

	public String getNodeType(Object obj) throws ContextException {
		// deprecated. If this object appears in the context more
		// than once, there is no guarantee that the correct context
		// fiType will be returned. Best not to have an orphaned
		// object.
		String path = getPath(obj);
		if (path == null)
			return null;
		return getAttributeValue(path, DATA_NODE_TYPE);
	}

	public List<String> metaassociations(String path) throws ContextException {
		Object val;
		List<String> values = new ArrayList<String>();
		// locate the context and context path for this key
		Object[] map = getContextMapping(path);
		Context cxt = (Context) map[0];
		String mappedKey = (String) map[1];
		List<String> e = localCompositeAttributes();
		for (String attName : e) {
			val = cxt.getMetaattributeValue(mappedKey, attName);
			if (val != null)
				values.add(attName + APS + val);
		}
		return values;
	}

	public boolean containsAssociation(String association)
			throws ContextException {
		return (getPathsWithAssociation(association).length > 0);
	}

	public String[] getPathsWithAssociation(String association)
			throws ContextException {
		return Contexts.getMarkedPaths(this, association);
	}

	/** {@inheritDoc} */
	public String getSubjectPath() {
		return subjectPath;
	}

	public void setSubjectPath(String path) {
		subjectPath = path;
	}

	/** {@inheritDoc} */
	public Object getSubjectValue() {
		return subjectValue;
	}

	/** {@inheritDoc} */
	public void setSubject(String path, Object value) {
		subjectPath = path;
		subjectValue = value;
	}

	public void updateValue(Object value) throws ContextException {
		Object initValue = null;
		T newVal = null;
		Object id = null;
		if (value instanceof Tuple2) {
			initValue = ((Tuple2) value).key();
			newVal = ((Tuple2<?, T>) value).value();
			updateValue(initValue, newVal, id);
		} else if (value instanceof Identifiable) {
			id = ((Identifiable) value).getId();
			updateValue(initValue, newVal, id);
		} else if (value instanceof Tuple2[]) {
			for (int i = 0; i < ((Tuple2[]) value).length; i++) {
				updateValue(((Tuple2[]) value)[i]);
			}
		} else if (value instanceof Identifiable[]) {
			for (int i = 0; i < ((Identifiable[]) value).length; i++) {
				updateValue(((Identifiable[]) value)[i]);
			}
		}
	}

	/**
	 * @param initValue
	 * @param newVal
	 * @param id
	 * @throws ContextException
	 */
	private void updateValue(Object initValue, T newVal, Object id)
			throws ContextException {
		Iterator i = keyIterator();
		while (i.hasNext()) {
			String key = (String) i.next();
			T val = (T)get(key);
			if (id == null) {
				// logger.info("initValue= "+initVal+" val = "+val);
				if (initValue.equals(val)) {
					if (initValue.getClass() != val.getClass())
						throw new ContextException(
								"The fiType of initial and new eval does not mach: "
										+ initValue.getClass() + ":"
										+ val.getClass());
//					logger.info("init val = " + initValue + " swapping from "
//							+ val + " to " + newVal + " at key = " + key);
					put(key, newVal);
				}
			} else {
				if (val instanceof Identifiable
						&& id.equals(((Identifiable) val).getId()))
//					logger.info("id = " + id + " eval changed to " + newVal);
					put(key, newVal);
			}
		}
	}

	public void reportException(Throwable t) {
		if (exertion != null)
			exertion.getControlContext().addException(t);
		else
			((ModelStrategy)mogramStrategy).exceptions.add(new ThrowableTrace(t));
	}

	public void reportException(String message, Throwable t) {
		if (exertion != null)
			exertion.getControlContext().addException(message, t);
		else
			((ModelStrategy)mogramStrategy).exceptions.add(new ThrowableTrace(message, t));
	}

	public void reportException(String message, Throwable t, ProviderInfo info) {
		ServiceException se = new ServiceException(message, t, info);
		if (exertion != null)
			exertion.getControlContext().addException(se);
		else
			((ModelStrategy)mogramStrategy).exceptions.add(new ThrowableTrace(se));
	}

	public void reportException(String message, Throwable t, Provider provider) {
		ServiceException se = new ServiceException(message, t,
				new ProviderInfo(((ServiceProvider)provider).getDelegate().getServiceInfo()));

		if (exertion != null)
			exertion.getControlContext().addException(se);
		else
			((ModelStrategy)mogramStrategy).exceptions.add(new ThrowableTrace(se));
	}

	public void reportException(String message, Throwable t, Provider provider,  ProviderInfo info) {
		ServiceException se = new ServiceException(message, t,
				new ProviderInfo(((ServiceProvider)provider).getDelegate().getServiceInfo()).append(info));

		if (exertion != null)
			exertion.getControlContext().addException(se);
		else
			((ModelStrategy)mogramStrategy).exceptions.add(new ThrowableTrace(se));
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see sorcer.service.Context#appendTrace(java.lang.String)
	 */
	@Override
	public void appendTrace(String footprint) {
		if (exertion != null)
			exertion.getControlContext().appendTrace(footprint);
		else
			mogramStrategy.appendTrace(footprint);
	}

	@Override
	public Context getCurrentContext() throws ContextException {
		return (Context) ObjectCloner.clone(updateContext());
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see sorcer.service.Context#getProvider()
	 */
	@Override
	public Provider getProvider() throws SignatureException {
		if (exertion != null)
			return ((NetSignature) exertion.getProcessSignature()).getService();
		else
			return null;
	}

	public void substitute(Arg... entries) throws SetterException {
		if (entries == null)
			return;
		ReturnPath rPath = null;
		for (Arg a : entries) {
			if (a instanceof ReturnPath) {
				rPath = (ReturnPath) a;
				break;
			}
		}
		if (rPath != null) setReturnPath(rPath);

		try {
			for (Arg e : entries) {
				if (e instanceof Tuple2) {
					T val = null;

					if (((Tuple2) e)._2 instanceof Evaluation)
						val = (T)((Evaluation) ((Tuple2) e).value()).getValue();
					else
						val = (T)((Tuple2) e).value();

					if (((Tuple2) e)._1 instanceof String) {
						if (asis((String) ((Tuple2) e)._1) instanceof Setter) {
							((Setter) asis((String) ((Tuple2) e)._1))
									.setValue(val);
						} else {
							putValue((String) ((Tuple2) e)._1, val);
						}
					}
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new SetterException(ex);
		}
	}

	@Override
	public Context getDataContext() throws ContextException {
		return this;
	}

	@Override
	public String describe() {
		return toString();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see sorcer.service.Context#getMarkedValues(java.lang.String)
	 */
	@Override
	public List<T> getMarkedValues(String association) throws ContextException {
		List<String> paths = markedPaths(association);
		if (paths == null && scope != null) {
			paths = scope.markedPaths(association);
		}
		List<T> values = new ArrayList<T>();
		for (String path : paths) {
			try {
				values.add(getValue(path));
			} catch (Exception ex) {
				throw new ContextException(ex);
			}
		}
		return values;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see sorcer.service.Context#setMetacontext(java.util.Hashtable)
	 */
	@Override
	public void setMetacontext(Map<String, Map<String, String>> metacontext) {
		this.metacontext = metacontext;
	}

	public int hashCode() {
		return mogramId.hashCode();
	}

	/**
	 * Record this context as updated if the related exertion is monitored.
	 *
	 * @throws RemoteException
	 * @throws MonitorException
	 */
	public void checkpoint() throws ContextException {
		MonitorUtil.checkpoint(this);
	}

	public T asis(String path) {
		T val = null;
		synchronized (this) {
			if (isRevaluable == true) {
				isRevaluable = false;
				val = get(path);
				isRevaluable = true;
			} else {
				val = get(path);
			}
		}
		// potentially context link
		if (val == null) {
			try {
				return (T) getValue0(path);
			} catch (ContextException e) {
				e.printStackTrace();
			}
			return null;
		} else {
			return val;
		}
	}

	@Override
	public Domain add(Identifiable... objects) throws ContextException, RemoteException {
		boolean changed = false;
		for (Identifiable obj : objects) {
			if (obj instanceof Entry) {
				putValue(obj.getName(), ((Entry) obj).asis());
			} else {
				putValue(obj.getName(), obj);
			}
		}
		if (changed) {
			isChanged = true;
		}
		return this;
	}
	
	public Entry entry(String path) {
		Object obj = null;
		if (path != null) {
			obj = data.get(path);
		}
		if (obj instanceof Entry) {
			return (Entry)obj;
		} else
			return null;
	}

	@Override
	public T get(String path) {
		if (path != null)
			return data.get(path);
		else
			return (T) Context.none;
	}

	public Context setOutValues(Context<T> context) throws ContextException,
			RemoteException {
		List<String> pl = ((ServiceContext) context).getOutPaths();
		for (String p : pl) {
			putValue(p, context.getValue(p));
		}
		return this;
	}

	public Proc getPar(String path) throws ContextException, RemoteException {
		return new Proc(path, this);
	}

	public T getValue(Arg... entries) throws EvaluationException, RemoteException {
		try {
			return getValue(null, entries);
		} catch (ContextException e) {
			throw new EvaluationException(e);
		}
	}

	public Object getEvalValue(String path) throws ContextException {
		// reimplement in subclasses
		return getValue(path);
	}

	public T getValue(String path, Arg... entries)
			throws ContextException {
		// first managed dependencies
		String currentPath = path;
		if (((ModelStrategy)mogramStrategy).dependers != null
				&& ((ModelStrategy)mogramStrategy).dependers.size() > 0) {
			for (Evaluation eval : ((ModelStrategy)mogramStrategy).dependers)  {
				try {
					eval.getValue(entries);
				} catch (RemoteException e) {
					throw new ContextException(e);
				}
			}
		}
		T obj = null;
		try {
			substitute(entries);
			if (currentPath == null) {
				if (((ModelStrategy)mogramStrategy).responsePaths != null
						&& ((ModelStrategy)mogramStrategy).responsePaths.size()>0) {
					if (((ModelStrategy)mogramStrategy).responsePaths.size() == 1)
						currentPath = ((ModelStrategy)mogramStrategy).responsePaths.get(0).getName();
					else
						return (T) getResponse();
				}
				else if (returnPath != null)
					return getReturnValue(entries);
				else if (entries.length == 1 && entries[0] instanceof Signature.Out) {
					return (T) getSubcontext(((Signature.Out)entries[0]).getExtPaths());
				} else {
					return (T) this;
				}
			}
			if (currentPath.startsWith("super")) {
				obj = (T) exertion.getScope().getValue(currentPath.substring(6));
			} else {
				obj = (T) getValue0(currentPath);
				if (obj instanceof Evaluation && isRevaluable) {
					if (obj instanceof Scopable) {
						Object scope = ((Scopable)obj).getScope();
						if (scope == null) {
							((Scopable)obj).setScope(this);
						} else {
							((Scopable)obj).getScope().append(this);
						}
					} else if (obj instanceof Entry
							&& ((Entry)obj).value() instanceof Scopable) {
						((Scopable)((Entry)obj).asis()).setScope(this);
					}
					obj = ((Evaluation<T>)obj).getValue(entries);
				} else if ((obj instanceof Paradigmatic)
						&& ((Paradigmatic) obj).isModeling()) {
					obj = ((Evaluation<T>)obj).getValue(entries);
				}
			}
			if (obj instanceof Reactive && ((Reactive)obj).isReactive()) {
				if (obj instanceof Entry && ((Entry)obj).value() instanceof Scopable)
					((Scopable)((Entry)obj).value()).setScope(this);
				obj = (T) ((Evaluation) obj).getValue(entries);
			}
			if (scope != null && (obj == Context.none || obj == null ))
				obj = (T ) scope.getValue(path, entries);

			return (T) obj;
		} catch (Throwable e) {
			logger.warn(e.getMessage());
			e.printStackTrace();
			return (T) Context.none;
		}
	}

	public Object getResponseAt(String path, Arg... entries) throws ContextException, RemoteException {
		return getValue(path, entries);
	}

	public Context getInConnector(Arg... args) throws ContextException, RemoteException {
		return ((ModelStrategy)mogramStrategy).getInConnector();
	}

	public Context getOutConnector(Arg... args) throws ContextException, RemoteException {
		return ((ModelStrategy)mogramStrategy).getOutConnector();
	}

	public Context getResponse(Arg... args) throws ContextException, RemoteException {
		Context result = null;
		if (((ModelStrategy)mogramStrategy).outConnector != null) {
			ServiceContext mc = null;
			try {
				mc = (ServiceContext) ObjectCloner.clone(((ModelStrategy)mogramStrategy).outConnector);
			} catch (Exception e) {
				throw new ContextException(e);
			}
			Iterator it = mc.entryIterator();
			while (it.hasNext()) {
				Map.Entry pairs = (Map.Entry) it.next();
				mc.putInValue((String) pairs.getKey(), getValue((String) pairs.getValue()));
			}
			if (((ModelStrategy)mogramStrategy).responsePaths != null
					&& ((ModelStrategy)mogramStrategy).responsePaths.size() > 0) {
				getMergedSubcontext(mc, ((ModelStrategy)mogramStrategy).responsePaths, args);
				((ModelStrategy)mogramStrategy).outcome = mc;
				((ModelStrategy)mogramStrategy).outcome.setModeling(true);
				result = ((ModelStrategy)mogramStrategy).outcome;
			}
		} else {
			if (((ModelStrategy)mogramStrategy).responsePaths != null
					&& ((ModelStrategy)mogramStrategy).responsePaths.size() > 0) {
				((ModelStrategy)mogramStrategy).outcome = getMergedSubcontext(null,
						((ModelStrategy)mogramStrategy).responsePaths, args);
			} else {
				substitute(args);
				((ModelStrategy)mogramStrategy).outcome = this;
			}
			result = ((ModelStrategy)mogramStrategy).outcome;
		}
		((ModelStrategy)mogramStrategy).outcome.setModeling(false);
		result.setName("Response of " + getClass().getSimpleName() + " " + name);
		return result;
	}

	public Object getResult() throws ContextException, RemoteException {
		((ModelStrategy)mogramStrategy).outcome.setModeling(false);
		return ((ModelStrategy)mogramStrategy).outcome;
	}

	public Context evaluate(Context inputContext, Arg... args) throws ContextException, RemoteException {
		Object pars = inputContext.getValue(argsPath);
		if (pars != null && args != Context.none)
			substitute((Arg[])args);
		Context inputs = inputContext.getInputs();
		this.append(inputs);
		getResponse();
		return this;
	}

	public Context getInputs() throws ContextException, RemoteException {
		List<String> paths = Contexts.getInPaths(this);
		Context<T> inputs = new ServiceContext();
		for (String path : paths)
			inputs.putValue(path, getValue(path));

		return inputs;
	}

	public Context getAllInputs() throws ContextException, RemoteException {
		List<String> paths = Contexts.getAllInPaths(this);
		Context<T> inputs = new ServiceContext();
		for (String path : paths)
			inputs.putValue(path, getValue(path));

		return inputs;
	}

	public Context getOutputs() throws ContextException, RemoteException {
		List<String> paths = Contexts.getOutPaths(this);
		Context<T> inputs = new ServiceContext();
		for (String path : paths)
			inputs.putValue(path, getValue(path));

		return inputs;
	}

	public Context getResponses(String path, Path... paths) throws ContextException, RemoteException {
		Context results = getMergedSubcontext(null, Arrays.asList(paths));
		putValue(path, results);
		return results;
	}

	public ServiceContext getInEntContext() throws ContextException {
		ServiceContext icxt = new ServiceContext();
		Iterator ei = entryIterator();
		Map.Entry<String, T> e;
		while (ei.hasNext()) {
			e = (Map.Entry<String, T>) ei.next();
			if (e.getValue() instanceof InputEntry) {
				icxt.putValue(e.getKey(), e.getValue());
			}
		}
		return icxt;
	}

	public ServiceContext getOutEntContext() throws ContextException {
		ServiceContext ocxt = new ServiceContext();
		Iterator ei = entryIterator();
		Map.Entry<String, T> e;
		while (ei.hasNext()) {
			e = (Map.Entry<String, T>) ei.next();
			if (e.getValue() instanceof OutputEntry) {
				ocxt.putValue(e.getKey(), e.getValue());
			}
		}
		return ocxt;
	}

	public String getCurrentPrefix() {
		return currentPrefix;
	}

	public void setCurrentPrefix(String currentPrefix) {
		this.currentPrefix = currentPrefix;
	}

	public Map<String, T> getData() {
		// to reimplemented in subclasses
		return data;
	}

	@Override
	public int size() {
		return data.size();
	}

	public String getPrefix() {
		if (prefix != null && prefix.length() > 0)
			return prefix + CPS;
		else
			return "";
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#getName()
	 */
	@Override
	public String getName() {
		return name;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#link(sorcer.service.Context, java.lang.String, java.lang.String)
	 */
	@Override
	public Object link(Context context, String atPath, String offset)
			throws ContextException {
		return putLink(atPath, context, offset);
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#addValue(sorcer.service.Identifiable)
	 */
	@Override
	public Object addValue(Identifiable value) throws ContextException {
		if (value instanceof Entry && !((Entry)value).isPersistent()) {
			return putValue(value.getName(), ((Entry)value).value());
		}
		return putValue(value.getName(), value);
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#putDbValue(java.lang.String, java.lang.Object)
	 */
	@Override
	public Object putDbValue(String path, Object value) throws ContextException, RemoteException {
		Proc procEntry = new Proc(path, value == null ? Context.none : value);
		procEntry.setPersistent(true);
		return putValue(path, procEntry);
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#putDbValue(java.lang.String, java.lang.Object, java.net.URL)
	 */
	@Override
	public Object putDbValue(String path, Object value, URL datastoreUrl)
			throws ContextException, RemoteException {
		Proc procEntry = new Proc(path, value == null ? Context.none : value);
		procEntry.setPersistent(true);
		procEntry.setDbURL(datastoreUrl);
		return putValue(path, procEntry);
	}

	public List<EntryList> getEntryLists() {
		return entryLists;
	}

	public void setEntryLists(List<EntryList> entryLists) {
		this.entryLists = entryLists;
	}

	public EntryList getEntryList(EntryList.Type type) {
		if (entryLists != null) {
			for (EntryList el : entryLists) {
				if (el.getType().equals(type))
					return el;
			}
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Contexter#getContext(sorcer.service.Context)
	 */
	@Override
	public Context<T> getContext(Context<T> contextTemplate)
			throws RemoteException, ContextException {
		Object val = null;
		for (String path : contextTemplate.getPaths()) {
			val = asis(path);
			if (val != null && val != Context.none)
				contextTemplate.putValue(path, asis(path));
		}
		return contextTemplate;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#addPar(sorcer.core.context.model.proc.Proc)
	 */
	@Override
	public Arg addProc(Arg arg) throws ContextException {
		Proc p = (Proc)arg;
		put(p.getName(), (T)p);
		if (p.getScope() == null || p.getScope().size() == 0)
			p.setScope(this);
		try {
			if (p.asis() instanceof Scopable) {
				Scopable si = (Scopable) p.asis();
				if (si.getScope() == null || si.getScope().size() == 0)
					((Scopable) p.asis()).setScope(this);
				else {
					((ServiceContext)si.getScope()).setScope(this);
				}
			}
		} catch (RemoteException e) {
			throw new ContextException(e);
		}
		isChanged = true;
		return p;
	}

	public Proc appendPar(Proc p) throws ContextException {
		put(p.getName(), (T)p);
		if (p.getScope() == null)
			p.setScope(new ProcModel(p.getName()).append(this));
		try {
			if (p.asis() instanceof ServiceInvoker) {
				((ServiceInvoker) p.asis()).setScope(this);
			}
		} catch (RemoteException e) {
			throw new ContextException(e);
		}
		isChanged = true;
		return p;
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Context#addPar(java.lang.String, java.lang.Object)
	 */
	@Override
	public Proc addProc(String path, Object value) throws ContextException {
		return new Proc(path, value, this);
	}


	@Override
	public String[] getMarkedPaths(String association)
			throws ContextException {
		return Contexts.getMarkedPaths(this, association);
	}

	public boolean isFinalized() {
		return isFinalized;
	}

	public void setFinalized(boolean isFinalized) {
		this.isFinalized = isFinalized;
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof Context))
			return false;

		if (keySet().size() != ((ServiceContext)object).keySet().size())
			return false;

		for (String  path : keySet()) {
			if (!asis(path).equals(((ServiceContext) object).asis(path)))
				return false;
		}
		return true;
	}

	public Signature getProcessSignature() {
		if (subjectValue instanceof Signature)
			return (Signature)subjectValue;
		else
			return null;
	}

	@Override
	public Context getContext() throws ContextException {
		return this;
	}

	@Override
	public <T extends Mogram> T exert(Transaction txn, Arg... entries) throws TransactionException,
			ExertionException, RemoteException {
		Signature signature = null;
		try {
			if (subjectValue instanceof Class) {
				signature = sig(subjectPath, subjectValue);
				return (T) ((Exertion)operator.xrt(name, signature, this).exert(txn, entries)).getContext();
			} else {
				// evaluates model outputs - response
				getResponse(entries);
				return (T) this;
			}
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	@Override
	public <T extends Mogram> T exert(Arg... entries) throws TransactionException,
			ExertionException, RemoteException {
		return exert(null, entries);
	}

	/* (non-Javadoc)
     * @see sorcer.service.Service#exert(sorcer.service.Exertion, net.jini.core.transaction.Transaction)
     */
    public <T extends Mogram> T exert(T mogram, Transaction txn, Arg... args) throws TransactionException,
            MogramException, RemoteException {
        try {
            if (mogram instanceof NetTask) {
                Task task = (NetTask)mogram;
                Class serviceType = task.getServiceType();
                if (provider != null) {
					Task out = ((ServiceProvider)provider).getDelegate().doTask(task, txn, args);
					// clearSessions provider execution scope
					out.getContext().setScope(null);
					return (T) out;
                } else if (Invocation.class.isAssignableFrom(serviceType)) {
                    Object out = ((Invocation)this).invoke(task.getContext(), args);
                    handleExertOutput(task, out);
                    return (T) task;
                } else if (Evaluation.class.isAssignableFrom(serviceType)) {
                    Object out = ((Evaluation)this).getValue(args);
                    handleExertOutput(task, out);
                    return (T) task;
                }
            }
            exertion.getContext().appendContext(this);
            return (T) exertion.exert(txn);
        } catch (Exception e) {
            e.printStackTrace();
            mogram.getContext().reportException(e);
            if (e instanceof Exception)
                mogram.setStatus(FAILED);
            else
                mogram.setStatus(ERROR);

            throw new ExertionException(e);
        }
    }

	private void handleExertOutput(Task task, Object result ) throws ContextException {
		ServiceContext dataContext = (ServiceContext) task.getDataContext();
		if (result instanceof Context) {
			ReturnPath rp = dataContext.getReturnPath();
			if (rp != null) {
				if (((Context) result).getValue(rp.path) != null) {
					dataContext.setReturnValue(((Context) result).getValue(rp.path));
				} else if (rp.outPaths != null && rp.outPaths.length > 0) {
					Context out = dataContext.getDirectionalSubcontext(rp.outPaths);
					dataContext.setReturnValue(out);
				}
			} else if (dataContext.getScope() != null) {
				dataContext.getScope().append((Context)result);
			} else {
				dataContext = (ServiceContext) result;
			}
		} else {
			dataContext.setReturnValue(result);
		}
		dataContext.updateContextWith(((ServiceSignature)task.getProcessSignature()).getOutConnector());
		task.setContext(dataContext);
		task.setStatus(DONE);
	}

	public <T extends Mogram> T exert(T mogram) throws TransactionException,
			MogramException, RemoteException {
		return (T) exert(exertion, null, (Arg[])null);
	}

	public Context updateInOutPaths(Path[] inpaths, Path[] outpaths) throws ContextException, RemoteException {
		if (containsPath(Condition._closure_)) {
			remove(Condition._closure_);
		}
		if (scope != null && scope.containsPath(Condition._closure_)) {
			scope.remove(Condition._closure_);
		}

		if (inpaths != null) {
			for (Path path : inpaths) {
				if (path.info != null && path.getType().equals(Path.Type.PATH)) {
					putInValue(path.getName(), (T) getValue(path.getName()), path.info.toString());
				} else {
					putInValue(path.getName(), (T) getValue(path.getName()));
				}
			}
		}

		if (outpaths != null) {
			for (Path path : outpaths) {
				if (path.info != null && path.getType().equals(Path.Type.PATH)) {
					putOutValue(path.getName(), (T) getValue(path.getName()), path.info.toString());
				} else {
					putOutValue(path.getName(), (T) getValue(path.getName()));
				}
			}
		}
		return this;
	}

	public Context updateContext(Path... paths) throws ContextException {
		if (containsPath(Condition._closure_)) {
			remove(Condition._closure_);
		}
		if (scope != null && scope.containsPath(Condition._closure_)) {
			scope.remove(Condition._closure_);
		}
		return this;
	}

	public boolean containsPath(String path) {
		return data.containsKey(path);
	}

//	public T get(Object key) {
//		return data.get(key);
//	}

	public Set<String> keySet() {
		return data.keySet();
	}

	public Collection<T> values() {
		return data.values();
	}

	public T put(String key, T value) {
		if (value == null)
			return data.put(key, (T)none);
		else
			return data.put(key, value);
	}

	public Variability.Type getType() {
		return type;
	}

	public void setType(Variability.Type type) {
		this.type = type;
	}

	public Iterator<String> keyIterator() {
		return keySet().iterator();
	}

	public Iterator<Map.Entry<String,T>> entryIterator() {
		return data.entrySet().iterator();
	}

	public void putAll(Context<T> context) {
		data.putAll((Map<? extends String, ? extends T>) ((ServiceContext) context).data);
	}

	public ModelStrategy getMogramStrategy() {
		return (ModelStrategy)mogramStrategy;
	}

	@Override
	public void addDependers(Evaluation... dependers) {
		((ModelStrategy)mogramStrategy).addDependers(dependers);
	}

	@Override
	public List<Evaluation> getDependers() {
		return ((ModelStrategy)mogramStrategy).getDependers();
	}

	public Direction getDirection() {
		return direction;
	}

	public void setDirection(Direction direction) {
		this.direction = direction;
	}

	public Entry<T> getEntry(String path) {
		return new Entry(path, data.get(path));
	}

	public String getSingletonPath() throws ContextException {
		if (data.size() == 1) {
			return getPaths().get(0);
		}
		return null;
	}

	public String getProviderName() throws RemoteException {
		if (provider == null)
			return name;
		else
			return provider.getProviderName();
	}

	public boolean compareTo(Object object) {
		return compareTo(object, 0.01);
	}

	public boolean compareTo(Object object, double delta) {
		if (object instanceof Context) {
			Iterator<String> ci = data.keySet().iterator();
			while (ci.hasNext()) {
				String path = ci.next();
				Object y = ((ServiceContext) object).data.get(path);
				Object x = data.get(path);
				if (x instanceof Double && y instanceof Double) {
					if (Math.abs((double) x - (double) y) > delta) {
						return false;
					}
				} else if (!x.equals(y)) {
					return false;
				}
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public Context getDomain(String name) throws ContextException {
        Object domain = data.get(name);
        if (domain instanceof Context) {
            return (Context) domain;
        }
        throw new ContextException("no such domain: " + name);
	}


	@Override
	public Object exec(Arg... args) throws MogramException, RemoteException {
		Context cxt = (Context) Arg.getServiceModel(args);
		if (cxt != null) {
			scope = cxt;
			return getResponse(args);
		} else {
			return getValue(args);
		}
	}

	public void clean() {
		exertion = null;
		if (scope != null) {
			((ServiceContext) scope).clean();
		}
	}
}
