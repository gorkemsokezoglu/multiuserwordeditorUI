package org.multiuserwordeditor.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Document {
    private String id;
    private String title;
    private String content;
    private String owner;
    private List<String> collaborators;
    private Map<String, Integer> cursorPositions;
    private Map<String, Selection> selections;
    private long lastModified;

    public Document() {
        this.collaborators = new ArrayList<>();
        this.cursorPositions = new HashMap<>();
        this.selections = new HashMap<>();
        this.lastModified = System.currentTimeMillis();
    }

    public Document(String id, String title) {
        this();
        this.id = id;
        this.title = title;
    }

    public Document(String id, String title, String content) {
        this(id, title);
        this.content = content;
    }

    public Document(String id, String title, String owner, boolean isOwner) {
        this();
        this.id = id;
        this.title = title;
        this.owner = owner;
        this.content = "";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.lastModified = System.currentTimeMillis();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        this.lastModified = System.currentTimeMillis();
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public List<String> getCollaborators() {
        return collaborators;
    }

    public void setCollaborators(List<String> collaborators) {
        this.collaborators = collaborators;
    }

    public void addCollaborator(String userId) {
        if (!collaborators.contains(userId)) {
            collaborators.add(userId);
        }
    }

    public void removeCollaborator(String userId) {
        collaborators.remove(userId);
        cursorPositions.remove(userId);
        selections.remove(userId);
    }

    public Map<String, Integer> getCursorPositions() {
        return cursorPositions;
    }

    public void setCursorPosition(String userId, int position) {
        cursorPositions.put(userId, position);
    }

    public Integer getCursorPosition(String userId) {
        return cursorPositions.get(userId);
    }

    public Map<String, Selection> getSelections() {
        return selections;
    }

    public void setSelection(String userId, int start, int end) {
        selections.put(userId, new Selection(start, end));
    }

    public Selection getSelection(String userId) {
        return selections.get(userId);
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public Document withId(String id) {
        this.id = id;
        return this;
    }

    public Document withTitle(String title) {
        setTitle(title);
        return this;
    }

    public Document withContent(String content) {
        setContent(content);
        return this;
    }

    public Document withOwner(String owner) {
        this.owner = owner;
        return this;
    }

    @Override
    public String toString() {
        return title + " (" + id + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Document document = (Document) o;
        return id != null && id.equals(document.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    public static class Selection {
        private int start;
        private int end;

        public Selection(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }

        public int getEnd() {
            return end;
        }

        public void setEnd(int end) {
            this.end = end;
        }
    }
} 