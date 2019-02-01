package edu.pjatk.inn.coffeemaker.impl;

public class Order {
    private int id;
    private Recipe recipe;


    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public void setRecipe(Recipe rec) {
        this.recipe = rec;
    }


}