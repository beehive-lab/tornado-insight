package com.tais.tornado_plugins.entity;

import java.util.List;

public class InspectionsContainer {

    private List<Inspector> inspectors;

    public List<Inspector> getInspections() {
        return inspectors;
    }

    public void setInspections(List<Inspector> inspectors) {
        this.inspectors = inspectors;
    }

    public static class Inspector {
        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
}

