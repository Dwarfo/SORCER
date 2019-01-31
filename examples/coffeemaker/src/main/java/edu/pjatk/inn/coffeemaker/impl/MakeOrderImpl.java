package edu.pjatk.inn.coffeemaker.impl;

import edu.pjatk.inn.coffeemaker.MakeOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.service.Context;
import sorcer.service.ContextException;
import edu.pjatk.inn.coffeemaker.impl.Order;
import java.rmi.RemoteException;


public class MakeOrderImpl implements MakeOrder {

    private final CoffeeMaker coffeeMaker = new CoffeeMaker();

    public CoffeeMaker getCoffeeMaker()
    {
        return coffeeMaker;
    }

    @Override
    public Context addOrder(Context context) throws RemoteException, ContextException {

        try {
            int id = (int) context.getValue("order/id");
            String name = (String) context.getValue("recipe/name");
            if (name == null) {
                throw new Exception();
            }

            Recipe recipe = coffeeMaker.getRecipeForName(name);
            if (recipe == null) {
                throw new Exception();
            }

            Order order = new Order();
            order.setRecipe(recipe);
            order.setId(id);

            boolean result = coffeeMaker.addOrder(order);

            if (context.getReturnPath() != null) {
                context.setReturnValue(result);
            }
        } catch (Exception e) {
            throw new ContextException();
        }

        return context;
    }

    @Override
    public Context removeOrder(Context context) throws RemoteException, ContextException {
        try {
            int id = (int) context.getValue("order/id");
            String name = (String) context.getValue("recipe/name");
            if (name == null) {
                throw new Exception();
            }

            Recipe recipe = coffeeMaker.getRecipeForName(name);
            if (recipe == null) {
                throw new Exception();
            }

            Order order = new Order();
            Order.setRecipe(recipe);
            Order.setId(id);

            boolean result = coffeeMaker.removeOrder(order);

            if (context.getReturnPath() != null) {
                context.setReturnValue(result);
            }
        } catch (Exception e) {
            throw new ContextException();
        }

        return context;
    }

    @Override
    public Context getOrder(Context context) throws RemoteException, ContextException {
        try {
            int id = (int) context.getValue("order/id");

            Order order = coffeeMaker.getOrder(id);
            if (order == null) {
                throw new Exception();
            }

            if (context.getReturnPath() != null) {
                context.setReturnValue(order);
            }
        } catch (Exception e) {
            throw new ContextException();
        }

        return context;
    }
}