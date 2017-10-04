package sorcer.core.context.model.srv;

import net.jini.core.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.co.tuple.MogramEntry;
import sorcer.co.tuple.SignatureEntry;
import sorcer.core.context.ApplicationDescription;
import sorcer.core.context.ServiceContext;
import sorcer.core.context.model.ent.Entry;
import sorcer.core.plexus.MorphFidelity;
import sorcer.service.*;
import sorcer.service.Signature.ReturnPath;
import sorcer.service.Domain;
import sorcer.service.modeling.Model;
import sorcer.service.modeling.Variability;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.Map;
import java.util.concurrent.Callable;

import static sorcer.eo.operator.task;

/**
 * Created by Mike Sobolewski on 4/14/15.
 */
public class Srv extends Entry<Object> implements Variability<Object>,
        Comparable<Object>, Reactive<Object>, Serializable {

    private static Logger logger = LoggerFactory.getLogger(Srv.class.getName());

    protected final String name;

    protected Object srvValue;

    protected String[] paths;

    protected ReturnPath returnPath;

    // srv fidelities
    protected Map<String, Object> fidelities;

    public Srv(String name) {
        super(name);
        this.name = name;
        type = Variability.Type.SRV;
    }

    public Srv(String name, String path,  Service service, String[] paths) {
        super(path, service);
        this.name = name;
        this.paths = paths;
        type = Variability.Type.SRV;
    }

    public Srv(String name, String path, Client service) {
        super(path, service);
        this.name = name;
        type = Variability.Type.SRV;
    }

    public Srv(String path, Object value, String[] paths) {
        super(path, value);
        this.name = path;
        this.paths = paths;
        type = Variability.Type.SRV;
    }
    public Srv(String path, Object value) {
        super(path, value);
        this.name = path;
        type = Variability.Type.SRV;
    }

    public Srv(String path, Object value, ReturnPath returnPath) {
        this(path, value);
        this.returnPath = returnPath;
    }

    public Srv(String name, String path, Object value, ReturnPath returnPath) {
        super(path, value);
        this.returnPath = returnPath;
        this.name = name;
        type = Variability.Type.SRV;
    }

    public Srv(String name, Object value, String path) {
        super(path, value);
        this.name = name;
    }

    public Srv(String name, Object value, String path, Type type) {
        this(name, value, path);
        this.type = type;
    }

    public Srv(String name, Model model, String path) {
        super(path, model);
        this.name = name;
        type = Variability.Type.SRV;
    }

    @Override
    public ApplicationDescription getDescription() {
        return null;
    }

    @Override
    public Class<?> getValueType() {
        return null;
    }

    public String[] getPaths() {
        return paths;
    }

    @Override
    public ArgSet getArgs() {
        return null;
    }

    @Override
    public void addArgs(ArgSet set) throws EvaluationException {

    }

    @Override
    public Object getArg(String varName) throws ArgException {
        return null;
    }

    @Override
    public boolean isValueCurrent() {
        return isValid;
    }

    @Override
    public void valueChanged(Object obj) throws EvaluationException, RemoteException {
        srvValue = obj;
    }

    public Mogram exert(Mogram mogram) throws TransactionException, MogramException, RemoteException {
        return exert(mogram, null);
    }

    @Override
    public void valueChanged() throws EvaluationException {
          isValid = false;
    }

    @Override
    public Object getPerturbedValue(String varName) throws EvaluationException, RemoteException {
        return null;
    }

    @Override
    public Object getValue(Arg... entries) throws EvaluationException, RemoteException {
        if (srvValue != null && isValid) {
            return srvValue;
        }
        try {
            substitute(entries);
            if (_2 instanceof Callable) {
                return ((Callable) _2).call();
            } else if (_2 instanceof SignatureEntry) {
                if (scope != null && scope instanceof SrvModel) {
                    try {
                        return ((SrvModel) scope).evalSignature(((SignatureEntry) _2)._2, _1);
                    } catch (Exception e) {
                        throw new EvaluationException(e);
                    }
                } else if (((SignatureEntry) _2).getContext() != null) {
                    try {
                        return execSignature(((SignatureEntry) _2)._2,
                            ((SignatureEntry) _2).getContext());
                    } catch (MogramException e) {
                        throw new EvaluationException(e);
                    }
                }
                throw new EvaluationException("No model available for entry: " + this);
            } else if (_2 instanceof MogramEntry) {
                Context cxt = ((Mogram) ((Entry) _2)._2).exert(entries).getContext();
                Object val = cxt.getValue(Context.RETURN);
                if (val != null) {
                    return val;
                } else {
                    return cxt;
                }
            } else if (_2 instanceof MorphFidelity) {
                return execMorphFidelity((MorphFidelity) _2, entries);
            } else {
                return super.getValue(entries);
            }
        } catch (Exception e) {
            throw new EvaluationException(e);
        }
    }

    private Object execMorphFidelity(MorphFidelity mFi, Arg... entries)
        throws ServiceException, RemoteException, TransactionException {
        Object obj = mFi.getSelect();
        Object out = null;
        if (obj instanceof Scopable) {
            ((Scopable)obj).setScope(scope);
            isValid(false);
        }
        if (obj instanceof Entry) {
            out = ((Entry) obj).getValue(entries);
        } else if (obj instanceof Mogram) {
            Context cxt = ((Mogram)obj).exert(entries).getContext();
            Object val = cxt.getValue(Context.RETURN);
            if (val != null) {
                return val;
            } else {
                return cxt;
            }
        }  else if (obj instanceof Service) {
            out = ((Service)obj).exec(entries);
        } else {
            return obj;
        }
        mFi.setChanged();
        mFi.notifyObservers(out);
        return out;
    }

    public Object execSignature(Signature sig, Context scope) throws MogramException {
        ReturnPath rp = (ReturnPath) sig.getReturnPath();
        Path[] ops = null;
        if (rp != null) {
            ops = ((ReturnPath) sig.getReturnPath()).outPaths;
        }
        Context incxt = scope;
        if (sig.getReturnPath() != null) {
            incxt.setReturnPath(sig.getReturnPath());
        }
        Context outcxt = null;
        try {
            outcxt = task(sig, incxt).exert().getContext();
            if (ops != null && ops.length > 0) {
                return outcxt.getDirectionalSubcontext(ops);
            } else if (sig.getReturnPath() != null) {
                return outcxt.getReturnValue();
            }
        } catch (Exception e) {
            throw new MogramException(e);
        }
        if (contextSelector != null) {
            try {
                return contextSelector.doSelect(outcxt);
            } catch (ContextException e) {
                throw new EvaluationException(e);
            }
        } else
            return outcxt;
    }

    @Override
    public Object exec(Arg... args) throws ServiceException, RemoteException {
        Domain mod = Arg.getServiceModel(args);
        if (mod != null) {
            if (mod instanceof SrvModel && _2 instanceof ValueCallable) {
                return ((ValueCallable) _2).call((Context) mod);
            } else if  (mod instanceof Context && _2 instanceof SignatureEntry) {
                return ((ServiceContext)mod).execSignature(((SignatureEntry) _2)._2);
            } else {
                _2 = mod;
                return getValue(args);
            }
        }
        return null;
    }

    public Object getSrvValue() {
        return srvValue;
    }

    public void setSrvValue(Object srvValue) {
        this.srvValue = srvValue;
    }

    public ReturnPath getReturnPath() {
        return returnPath;
    }

    public void setReturnPath(ReturnPath returnPath) {
        this.returnPath = returnPath;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getPath() {
        return _1;
    }

    @Override
    public double getPerturbation() {
        return 0;
    }
}
