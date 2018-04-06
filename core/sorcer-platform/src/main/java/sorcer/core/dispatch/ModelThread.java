/*
 * Copyright 2013 the original author or authors.
 * Copyright 2013 SorcerSoft.org.
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

package sorcer.core.dispatch;

import net.jini.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.core.DispatchResult;
import sorcer.core.Dispatcher;
import sorcer.core.provider.Provider;
import sorcer.core.provider.ServiceProvider;
import sorcer.service.*;

public class ModelThread extends Thread {
	private final static Logger logger = LoggerFactory.getLogger(ModelThread.class
			.getName());

	private static final int SLEEP_TIME = 250;
	// doBlock method calls internally
	private Task task;
	private Arg[] args;
	private Task result;
	Provider provider;

	public ModelThread(Task task, Provider provider, Arg... args) {
		this.args = args;
		this.task = task;
		this.provider = provider;
	}

	public void run() {
		logger.debug("*** Exertion dispatcher started with control context ***\n"
				+ task.getControlContext());
		Dispatcher dispatcher = null;
		try {
			String exertionDeploymentConfig = null;
			if (task.isProvisionable()) {
				try {
					exertionDeploymentConfig =
							(String)((ServiceProvider)provider).getProviderConfiguration().getEntry("sorcer.core.provider.ServiceProvider",
									"exertionDeploymentConfig",
									String.class,
									null);
				} catch (ConfigurationException e1) {
					logger.warn("Unable to read property from configuration", e1);
				}
			}
			if (exertionDeploymentConfig != null)
				dispatcher = MogramDispatcherFactory.getFactory().createDispatcher(task, provider, exertionDeploymentConfig);
			else
				dispatcher = MogramDispatcherFactory.getFactory().createDispatcher(task, provider);

            dispatcher.exec(args);
            DispatchResult result = dispatcher.getResult();

			/*int COUNT = 1000;
			int count = COUNT;
			while (dispatcher.getState() != Exec.DONE
					&& dispatcher.getState() != Exec.FAILED
					&& dispatcher.getState() != Exec.SUSPENDED) {
				count--;
				if (count < 0) {
					logger.debug("*** Concatenator's Exertion Dispatcher waiting in state: "
							+ dispatcher.getState());
					count = COUNT;
				}
				Thread.sleep(SLEEP_TIME);
			} */

			logger.debug("*** Dispatcher exit state = " + dispatcher.getClass().getName()  + " state: " + result.state
					+ " for block***\n" + task.getControlContext());
            this.result = (Task) result.exertion;
        } catch (DispatcherException de) {
			de.printStackTrace();
		}
		//result = (Block) dispatcher.getMogram();
	}

	public Task getTask() {
		return task;
	}

	public Task getResult() throws ContextException {
		return result;
	}
}
