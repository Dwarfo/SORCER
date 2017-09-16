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

package sorcer.service;

import sorcer.service.modeling.Functionality;
import sorcer.service.modeling.Valuation;

import java.io.Serializable;
import java.rmi.RemoteException;

/**
 * An pair of objects as an identifiable item.
 *
 * @author Mike Sobolewski
 */
public class Entry<K, I> implements Computable<I>, net.jini.core.entry.Entry, Serializable, Identifiable, Arg {
	private  static final long serialVersionUID =  1L;

	protected K key = null;

	protected I item = null;

    protected boolean isPersistent = false;

    protected Class valClass;

    protected Functionality.Type type = Functionality.Type.VAL;

    public int index;

    protected Object annotation;

    protected Context scope;

    // when scope of this entry is changed then is not valid
    protected boolean isValid = true;

    public Entry() {
	}

    public Entry(K key) {
        this.key = key;
    }

    public Entry(K key, I item) {
		this.key = key;
		this.item = item;
	}

    public K getKey() {
		return key;
	}

    public K key() {
        return key;
    }

    public K path() {
        return key;
    }

	public void setKey(K key) {
		this.key = key;
	}

	public I getItem() {
		return item;
	}

    public I get() {
        return item;
    }

    // polimorphic evaluation of all entries that may depend on
    // implemented extending interfaces
    public I compute(Arg... entries) throws EvaluationException {
        return item;
    }

    public I asis() {
        return item;
    }

    public void setItem(I item) {
		this.item = item;
	}

    public void set(I item) {
        this.item = item;
    }

	@Override
	public String toString() {
		return "[" + key + ":" + item + "]";
	}

	public boolean isPersistent() {
		return isPersistent;
	}

	public void setPersistent(boolean isPersistent) {
		this.isPersistent = isPersistent;
	}

	public Object exec(Arg... entries) throws ServiceException, RemoteException {
		return item;
	}

	@Override
	public Object getId() {
		return key.toString();
	}

	@Override
	public String getName() {
		return key.toString();
	}

    public Functionality.Type getType() {
        return type;
    }

    public Object annotation() {
        return annotation;
    }

    public void annotation(Object annotation) {
        this.annotation = annotation;
    }

    public void setType(Functionality.Type type) {
        this.type = type;
    }

    public Class getValClass() {
        return valClass;
    }

    public void setValClass(Class valClass) {
        this.valClass = valClass;
    }

    public Context getScope() {
        return scope;
    }

    public void setScope(Context scope) {
        this.scope = scope;
    }

    public boolean isValid() {
        return isValid;
    }

    public void isValid(boolean state) {
        isValid = state;
        if (item  instanceof Entry) {
            ((Entry)item).isValid = state;
        }
    }

    @Override
    public int hashCode() {
        return 2 * 31 + key.hashCode() + item.hashCode();
    }

    public boolean equals(Object object) {
        if (object instanceof Entry) {
            if (item != null && ((Entry) object).item == null) {
                return false;
            } else if (item == null && ((Entry) object).item != null) {
                return false;
            } else if (((Entry) object).key.equals(key)
                    && ((Entry) object).item == item) {
                return true;
            } else if (((Entry) object).key.equals(key)
                    && ((Entry) object).item.equals(item)) {
                return true;
            }
        }
        return false;
    }

}