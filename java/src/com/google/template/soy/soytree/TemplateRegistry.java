/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.shared.internal.DelTemplateSelector;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A registry or index of all templates in a Soy tree.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class TemplateRegistry {

  private static final SoyErrorKind DUPLICATE_TEMPLATES =
      SoyErrorKind.of("Template/element ''{0}'' already defined at {1}.");
  private static final SoyErrorKind TEMPLATE_OR_ELEMENT_AND_DELTEMPLATE_WITH_SAME_NAME =
      SoyErrorKind.of("Found deltemplate {0} with the same name as a template/element at {1}.");
  private static final SoyErrorKind DUPLICATE_DEFAULT_DELEGATE_TEMPLATES =
      SoyErrorKind.of("Delegate template ''{0}'' already has a default defined at {1}.");
  private static final SoyErrorKind DUPLICATE_DELEGATE_TEMPLATES_IN_DELPACKAGE =
      SoyErrorKind.of(
          "Delegate template ''{0}'' already defined in delpackage {1}: {2}",
          StyleAllowance.NO_PUNCTUATION);

  /** Map from basic template or element name to node. */
  private final ImmutableMap<String, TemplateMetadata> basicTemplatesOrElementsMap;

  private final DelTemplateSelector<TemplateMetadata> delTemplateSelector;
  private final ImmutableList<TemplateMetadata> allTemplates;

  /**
   * Constructor.
   *
   * @param soyTree The Soy tree from which to build a template registry.
   */
  public TemplateRegistry(SoyFileSetNode soyTree, ErrorReporter errorReporter) {

    // ------ Iterate through all templates to collect data. ------
    ImmutableList.Builder<TemplateMetadata> allTemplatesBuilder = ImmutableList.builder();
    DelTemplateSelector.Builder<TemplateMetadata> delTemplateSelectorBuilder =
        new DelTemplateSelector.Builder<>();
    Map<String, TemplateMetadata> basicTemplatesOrElementsMap = new LinkedHashMap<>();
    Multimap<String, TemplateMetadata> delegateTemplates = HashMultimap.create();
    for (SoyFileNode soyFile : soyTree.getChildren()) {
      for (TemplateNode template : soyFile.getChildren()) {
        TemplateMetadata templateObject = TemplateMetadata.fromTemplate(template);
        allTemplatesBuilder.add(templateObject);
        switch (templateObject.getTemplateKind()) {
          case BASIC:
          case ELEMENT:
            // Case 1: Basic Template or Element node
            TemplateMetadata prev =
                basicTemplatesOrElementsMap.put(templateObject.getTemplateName(), templateObject);
            if (prev != null) {
              errorReporter.report(
                  template.getSourceLocation(),
                  DUPLICATE_TEMPLATES,
                  template.getTemplateName(),
                  prev.getSourceLocation());
            }
            break;
          case DELTEMPLATE:
            // Case 2: Delegate template.
            String delTemplateName = templateObject.getDelTemplateName();
            String delPackageName = templateObject.getDelPackageName();
            String variant = templateObject.getDelTemplateVariant();
            TemplateMetadata previous;
          if (delPackageName == null) {
              // default delegate
              previous =
                  delTemplateSelectorBuilder.addDefault(delTemplateName, variant, templateObject);
            if (previous != null) {
                errorReporter.report(
                    templateObject.getSourceLocation(),
                    DUPLICATE_DEFAULT_DELEGATE_TEMPLATES,
                    delTemplateName,
                    previous.getSourceLocation());
            }
          } else {
              previous =
                  delTemplateSelectorBuilder.add(
                      delTemplateName, delPackageName, variant, templateObject);
            if (previous != null) {
                errorReporter.report(
                    templateObject.getSourceLocation(),
                    DUPLICATE_DELEGATE_TEMPLATES_IN_DELPACKAGE,
                    delTemplateName,
                    delPackageName,
                    previous.getSourceLocation());
            }
          }
            delegateTemplates.put(delTemplateName, templateObject);
            break;
        }
      }
    }
    // make sure no basic nodes conflict with deltemplates
    for (Map.Entry<String, TemplateMetadata> entry : delegateTemplates.entries()) {
      TemplateMetadata node = basicTemplatesOrElementsMap.get(entry.getKey());
      if (node != null) {
        errorReporter.report(
            entry.getValue().getSourceLocation(),
            TEMPLATE_OR_ELEMENT_AND_DELTEMPLATE_WITH_SAME_NAME,
            entry.getKey(),
            node.getSourceLocation());
      }
    }

    // ------ Build the final data structures. ------

    this.basicTemplatesOrElementsMap = ImmutableMap.copyOf(basicTemplatesOrElementsMap);
    this.delTemplateSelector = delTemplateSelectorBuilder.build();
    this.allTemplates = allTemplatesBuilder.build();
  }

  /** Returns all basic template names. */
  public ImmutableSet<String> getBasicTemplateOrElementNames() {
    return basicTemplatesOrElementsMap.keySet();
  }

  /** Look up possible targets for a call. */
  public ImmutableList<TemplateMetadata> getTemplates(CallNode node) {
    if (node instanceof CallBasicNode) {
      String calleeName = ((CallBasicNode) node).getCalleeName();
      TemplateMetadata template = basicTemplatesOrElementsMap.get(calleeName);
      return template == null ? ImmutableList.of() : ImmutableList.of(template);
    } else {
      String calleeName = ((CallDelegateNode) node).getDelCalleeName();
      return delTemplateSelector.delTemplateNameToValues().get(calleeName);
    }
  }

  /**
   * Retrieves a template or element given the template name.
   *
   * @param templateName The basic template name to retrieve.
   * @return The corresponding template/element, or null if the name is not defined.
   */
  @Nullable
  public TemplateMetadata getBasicTemplateOrElement(String templateName) {
    return basicTemplatesOrElementsMap.get(templateName);
  }

  /** Returns a multimap from delegate template name to set of keys. */
  public DelTemplateSelector<TemplateMetadata> getDelTemplateSelector() {
    return delTemplateSelector;
  }

  /**
   * Returns all registered templates ({@link TemplateBasicNode basic} and {@link
   * TemplateDelegateNode delegate} nodes), in no particular order.
   */
  public ImmutableList<TemplateMetadata> getAllTemplates() {
    return allTemplates;
  }

  /**
   * Gets the content kind that a call results in. If used with delegate calls, the delegate
   * templates must use strict autoescaping. This relies on the fact that all delegate calls must
   * have the same kind when using strict autoescaping. This is enforced by CheckDelegatesPass.
   *
   * @param node The {@link CallBasicNode} or {@link CallDelegateNode}.
   * @return The kind of content that the call results in.
   */
  public Optional<SanitizedContentKind> getCallContentKind(CallNode node) {
    ImmutableList<TemplateMetadata> templateNodes = getTemplates(node);
    // For per-file compilation, we may not have any of the delegate templates in the compilation
    // unit.
    if (!templateNodes.isEmpty()) {
      return Optional.fromNullable(templateNodes.get(0).getContentKind());
    }
    // The template node may be null if the template is being compiled in isolation.
    return Optional.absent();
  }
}
