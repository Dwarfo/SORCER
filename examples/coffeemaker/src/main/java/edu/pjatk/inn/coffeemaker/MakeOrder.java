package edu.pjatk.inn.coffeemaker;

import sorcer.service.Context;
import sorcer.service.ContextException;

import java.rmi.RemoteException;

public interface MakeOrder {

    public Context addOrder(Context context) throws RemoteException, ContextException;
    public Context removeOrder(Context context) throws RemoteException, ContextException;
    public Context getOrder(Context context) throws RemoteException, ContextException;

}