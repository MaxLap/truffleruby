/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.language.objects.ObjectGraphNode;

public class RubyConstant implements ObjectGraphNode {

    private final DynamicObject declaringModule;
    private final Object value;
    private final boolean isPrivate;
    private final boolean isDeprecated;

    private final boolean autoload;
    private volatile ReentrantLock autoloadLock;
    /** A autoload constant can become "undefined" after the autoload loads the file but the constant is not defined by the file */
    private final boolean undefined;

    private final SourceSection sourceSection;

    public RubyConstant(DynamicObject declaringModule, Object value, boolean isPrivate, boolean autoload, boolean isDeprecated, SourceSection sourceSection) {
        this(declaringModule, value, isPrivate, autoload, false, isDeprecated, sourceSection);
    }

    private RubyConstant(DynamicObject declaringModule, Object value, boolean isPrivate, boolean autoload, boolean undefined, boolean isDeprecated, SourceSection sourceSection) {
        assert RubyGuards.isRubyModule(declaringModule);
        this.declaringModule = declaringModule;
        this.value = value;
        this.isPrivate = isPrivate;
        this.isDeprecated = isDeprecated;
        this.autoload = autoload;
        this.undefined = undefined;
        this.sourceSection = sourceSection;
    }

    public DynamicObject getDeclaringModule() {
        return declaringModule;
    }

    public boolean hasValue() {
        return !autoload && !undefined;
    }

    public Object getValue() {
        assert !autoload && !undefined;
        return value;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public boolean isDeprecated() {
        return isDeprecated;
    }

    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public RubyConstant withPrivate(boolean isPrivate) {
        if (isPrivate == this.isPrivate) {
            return this;
        } else {
            return new RubyConstant(declaringModule, value, isPrivate, autoload, undefined, isDeprecated, sourceSection);
        }
    }

    public RubyConstant withDeprecated() {
        if (this.isDeprecated()) {
            return this;
        } else {
            return new RubyConstant(declaringModule, value, isPrivate, autoload, undefined, true, sourceSection);
        }
    }

    public RubyConstant undefined() {
        assert autoload;
        return new RubyConstant(declaringModule, null, isPrivate, false, true, isDeprecated, sourceSection);
    }

    @TruffleBoundary
    public boolean isVisibleTo(RubyContext context, LexicalScope lexicalScope, DynamicObject module) {
        assert RubyGuards.isRubyModule(module);
        assert lexicalScope == null || lexicalScope.getLiveModule() == module;

        if (!isPrivate) {
            return true;
        }

        // Look in lexical scope
        if (lexicalScope != null) {
            while (lexicalScope != context.getRootLexicalScope()) {
                if (lexicalScope.getLiveModule() == declaringModule) {
                    return true;
                }
                lexicalScope = lexicalScope.getParent();
            }
        }

        // Look in ancestors
        if (RubyGuards.isRubyClass(module)) {
            for (DynamicObject included : Layouts.MODULE.getFields(module).ancestors()) {
                if (included != module && included == declaringModule) {
                    return true;
                }
            }
        }

        // Allow Object constants if looking with lexical scope.
        if (lexicalScope != null && context.getCoreLibrary().getObjectClass() == declaringModule) {
            return true;
        }

        return false;
    }

    public boolean isUndefined() {
        return undefined;
    }

    public boolean isAutoload() {
        return autoload;
    }

    public DynamicObject getAutoloadPath() {
        assert autoload;
        final Object feature = value;
        assert RubyGuards.isRubyString(feature);
        return (DynamicObject) feature;
    }

    private ReentrantLock getAutoloadLock() {
        synchronized (this) {
            if (autoloadLock == null) {
                autoloadLock = new ReentrantLock();
            }
        }
        return autoloadLock;
    }

    @TruffleBoundary
    public void startAutoLoad() {
        getAutoloadLock().lock();
    }

    @TruffleBoundary
    public void stopAutoLoad() {
        getAutoloadLock().unlock();
    }

    public boolean isAutoloading() {
        return autoloadLock != null && autoloadLock.isLocked();
    }

    public boolean isAutoloadingThread() {
        return autoloadLock != null && autoloadLock.isHeldByCurrentThread();
    }

    @Override
    public void getAdjacentObjects(Set<DynamicObject> adjacent) {
        if (value instanceof DynamicObject) {
            adjacent.add((DynamicObject) value);
        }
    }

}
