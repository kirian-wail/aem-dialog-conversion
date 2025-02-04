/*
 *  (c) 2014 Adobe. All rights reserved.
 *  This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License. You may obtain a copy
 *  of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under
 *  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *  OF ANY KIND, either express or implied. See the License for the specific language
 *  governing permissions and limitations under the License.
 */
package com.adobe.cq.dialogconversion.impl.rules;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.commons.flat.TreeTraverser;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.dialogconversion.DialogRewriteException;
import com.adobe.cq.dialogconversion.DialogRewriteRule;
import com.adobe.cq.dialogconversion.DialogRewriteUtils;
import com.day.cq.commons.jcr.JcrUtil;

/**
 * A rule that rewrites a tree based on a given node structure. The node structure has the following form:
 *
 * <pre>
 * rule
 *   - jcr:primaryType = nt:unstructured
 *   - cq:rewriteRanking = 4
 *   + patterns
 *     - jcr:primaryType = nt:unstructured
 *     + foo
 *       - ...
 *       + ...
 *     + foo1
 *       - ...
 *       + ...
 *   + replacement
 *     + bar
 *       - ...
 *       + ...
 * </pre>
 *
 * <p>
 * This example defines a rule containing two patterns (the trees rooted at <code>foo</code> and <code>foo1</code>) and a replacement (the tree rooted at
 * <code>bar</code>). The pattern and replacement trees are arbitrary trees containing nodes and properties. The rule matches a subtree if any of the defined
 * patterns matches. In order for a pattern to match, the tree in question must contain the same nodes as the pattern (matching names, except for the root), and
 * all properties defined in the pattern must match the properties on the tree. A node in a pattern can be marked as optional by setting
 * <code>cq:rewriteOptional</code> to <code>true</code>, in which case it doesn't necessarily have to present for a tree to match.
 * </p>
 *
 * <p>
 * In the case of a match, the matched subtree (called original tree) will be substituted by the replacement. The replacement tree can define mapped properties
 * that will inherit the value of a property in the original tree. They need to be of type <code>string</code> and have the following format:
 * <code>${&lt;path&gt;}</code>. If the referenced property doesn't exist in the original tree, then the property is omitted. Alternatively, a default value can
 * be specified for that case (only possible for <code>string</code> properties): <code>${&lt;path&gt;:&lt;default&gt;}</code>. Properties that contain ':'
 * characters can be single quoted to avoid conflict with providing a default value. Boolean properties are negated if the expression is prefixed with
 * <code>!</code>. Mapped properties can also be multivalued, in which case they will be assigned the value of the first property that exists in the original
 * tree. The following example illustrates mapping properties:
 * </p>
 *
 * <pre>
 * rule
 *   ...
 *   + replacement
 *     + bar
 *       - prop = ${./some/prop}
 *         // 'prop' will be assigned the value of 'some/prop' in the original tree
 *       - negated = !${./some/boolean/prop}
 *         // 'negated' will be assigned the negated value of 'some/boolean/prop' in the original tree
 *       - default = ${./some/prop:default string value}
 *         // 'default' will be assigned the value of 'some/prop' if it exists, else the string 'default string'
 *       - multi = [${./some/prop1}, ${./some/prop2}]
 *         // 'multi' will be assigned the value of 'some/prop1' if it exists, else the value of 'some/prop2'
 * </pre>
 *
 * The replacement tree supports following special properties:
 *
 * <ul>
 * <li><code>cq:rewriteMapChildren</code> (string)<br />
 * Copies the children of the referenced node in the original tree to the node containing this property (e.g. <code>cq:rewriteMapChildren=./items</code> will
 * copy the children of <code>./items</code> to the current node).</li>
 * <li><code>cq:rewriteFinal</code> (boolean)<br />
 * Set this property on a node that is final and can be disregarded for the rest of the conversion as an optimization measure. When placed on the replacement
 * node itself (i.e. on <code>rule/replacement</code>), the whole replacement tree is considered final.</li>
 * <li><code>cq:rewriteCommonAttrs</code> (boolean)<br />
 * Set this property on the replacement node (<code>rule/replacement</code>) to map relevant properties of the original root node to Granite common attribute
 * equivalents in the copy root. It will handle data attributes by copying/creating the granite:data subnode on the target and writing data-* properties there.
 * </li>
 * <li><code>cq:rewriteRenderCondition</code> (boolean)<br />
 * Set this property on the replacement node (<code>rule/replacement</code>) to copy any render condition (rendercondition or granite:rendercondition) child
 * node from the original root node to a granite:rendercondition child of the copy root.</li>
 * </ul>
 *
 * In addition, a special <code><cq:rewriteProperties></code> node can be added to a replacement node to define string rewrites for mapped properties in the
 * result. The node is removed from the replacement. The properties of the <code><cq:rewriteProperties></code> node must be named the same as those which they
 * are rewriting and accept a string array with two parameters:
 *
 * - pattern: regexp to match against. e.g. "(?:coral-Icon--)(.+)" - replacement: provided to the matcher <code>replaceAll</code> function. e.g. "$1"
 *
 * Example:
 *
 * <pre>
 * rule
 *   ...
 *   + replacement
 *     + bar
 *       - icon = ${./icon}
 *       + cq:rewriteProperties
 *         - icon = [(?:coral-Icon--)(.+), $1]
 * </pre>
 *
 */
public class NodeBasedRewriteRule implements DialogRewriteRule {

	// pattern that matches the regex for mapped properties: ${<path>}
	private static final Pattern MAPPED_PATTERN = Pattern.compile("^(\\!{0,1})\\$\\{(\'.*?\'|.*?)(:(.+))?\\}$");

	// special properties
	private static final String PROPERTY_RANKING = "cq:rewriteRanking";
	private static final String PROPERTY_OPTIONAL = "cq:rewriteOptional";
	private static final String PROPERTY_MAP_CHILDREN = "cq:rewriteMapChildren";
	private static final String PROPERTY_IS_FINAL = "cq:rewriteFinal";
	private static final String PROPERTY_COMMON_ATTRS = "cq:rewriteCommonAttrs";
	private static final String PROPERTY_RENDER_CONDITION = "cq:rewriteRenderCondition";

	// special nodes
	private static final String NN_CQ_REWRITE_PROPERTIES = "cq:rewriteProperties";

	// node names
	private static final String NN_RENDER_CONDITION = "rendercondition";
	private static final String NN_GRANITE_RENDER_CONDITION = "granite:rendercondition";
	private static final String NN_GRANITE_DATA = "granite:data";

	// Granite
	private static final String[] GRANITE_COMMON_ATTR_PROPERTIES = { "id", "rel", "class", "title", "hidden", "itemscope", "itemtype", "itemprop" };
	private static final String RENDER_CONDITION_CORAL2_RESOURCE_TYPE_PREFIX = "granite/ui/components/foundation/renderconditions";
	private static final String RENDER_CONDITION_CORAL3_RESOURCE_TYPE_PREFIX = "granite/ui/components/coral/foundation/renderconditions";
	private static final String DATA_PREFIX = "data-";

	private final Logger logger = LoggerFactory.getLogger(NodeBasedRewriteRule.class);

	private final Node ruleNode;
	private Integer ranking = null;

	public NodeBasedRewriteRule(final Node ruleNode) {
		this.ruleNode = ruleNode;
	}

	@Override
	public boolean matches(final Node root) throws RepositoryException {
		if (!this.ruleNode.hasNode("patterns")) {
			// the 'patterns' subnode does not exist
			return false;
		}
		final Node patterns = this.ruleNode.getNode("patterns");
		if (!patterns.hasNodes()) {
			// no patterns are defined
			return false;
		}
		// iterate over all defined patterns
		final NodeIterator iterator = patterns.getNodes();
		while (iterator.hasNext()) {
			final Node pattern = iterator.nextNode();
			if (this.matches(root, pattern)) {
				return true;
			}
		}
		// no pattern matched
		return false;
	}

	private boolean matches(final Node root, final Node pattern) throws RepositoryException {
		// check if the primary types match
		if (!DialogRewriteUtils.hasPrimaryType(root, pattern.getPrimaryNodeType().getName())) {
			return false;
		}

		// check that all properties of the pattern match
		final PropertyIterator propertyIterator = pattern.getProperties();
		while (propertyIterator.hasNext()) {
			final Property property = propertyIterator.nextProperty();
			final String name = property.getName();
			if (property.getDefinition().isProtected()) {
				// skip protected properties
				continue;
			}
			if (PROPERTY_OPTIONAL.equals(name)) {
				// skip cq:rewriteOptional property
				continue;
			}
			if (!root.hasProperty(name)) {
				// property present on pattern does not exist in tree
				return false;
			}
			if (!root.getProperty(name).getValue().equals(property.getValue())) {
				// property values on pattern and tree differ
				return false;
			}
		}

		// check that the tree contains all children defined in the pattern (optimization measure, before
		// checking all children recursively)
		NodeIterator nodeIterator = pattern.getNodes();
		while (nodeIterator.hasNext()) {
			final Node child = nodeIterator.nextNode();
			// if the node is marked as optional, we can skip the check
			if (child.hasProperty(PROPERTY_OPTIONAL) && child.getProperty(PROPERTY_OPTIONAL).getBoolean()) {
				continue;
			}
			if (!root.hasNode(child.getName())) {
				// this child is not present in subject tree
				return false;
			}
		}

		// check child nodes recursively
		nodeIterator = pattern.getNodes();
		while (nodeIterator.hasNext()) {
			final Node child = nodeIterator.nextNode();
			// if the node is marked as optional and is not present, then we skip it
			if (child.hasProperty(PROPERTY_OPTIONAL) && child.getProperty(PROPERTY_OPTIONAL).getBoolean() && !root.hasNode(child.getName())) {
				continue;
			}
			return this.matches(root.getNode(child.getName()), child);
		}

		// base case (leaf node)
		return true;
	}

	@Override
	public Node applyTo(final Node root, final Set<Node> finalNodes) throws DialogRewriteException, RepositoryException {
		// check if the 'replacement' node exists
		if (!this.ruleNode.hasNode("replacement")) {
			throw new DialogRewriteException("The rule does not define a replacement node");
		}

		// if the replacement node has no children, we replace the tree by the empty tree,
		// i.e. we remove the original tree
		final Node replacement = this.ruleNode.getNode("replacement");
		if (!replacement.hasNodes()) {
			root.remove();
			return null;
		}

		// true if the replacement tree is final and all its nodes are excluded from
		// further processing by the algorithm
		boolean treeIsFinal = false;
		if (replacement.hasProperty(PROPERTY_IS_FINAL)) {
			treeIsFinal = replacement.getProperty(PROPERTY_IS_FINAL).getBoolean();
		}

		/**
		 * Approach: - we move (rename) the tree to be rewritten to a temporary name - we copy the replacement tree to be a new child of the original tree's
		 * parent - we process the copied replacement tree (mapped properties, children etc) - at the end, we remove the original tree
		 */

		// move (rename) original tree
		final Node parent = root.getParent();
		final String rootName = root.getName();
		DialogRewriteUtils.rename(root);

		// copy replacement to original tree under original name
		final Node replacementNext = replacement.getNodes().nextNode();
		final Node copy = JcrUtil.copy(replacementNext, parent, rootName);

		// common attribute mapping
		if (replacement.hasProperty(PROPERTY_COMMON_ATTRS)) {
			this.addCommonAttrMappings(root, copy);
		}

		// render condition mapping
		if (replacement.hasProperty(PROPERTY_RENDER_CONDITION)) {
			if (root.hasNode(NN_GRANITE_RENDER_CONDITION) || root.hasNode(NN_RENDER_CONDITION)) {
				final Node renderConditionRoot = root.hasNode(NN_GRANITE_RENDER_CONDITION) ? root.getNode(NN_GRANITE_RENDER_CONDITION)
						: root.getNode(NN_RENDER_CONDITION);
				final Node renderConditionCopy = JcrUtil.copy(renderConditionRoot, copy, NN_GRANITE_RENDER_CONDITION);

				// convert render condition resource types recursively
				final TreeTraverser renderConditionTraverser = new TreeTraverser(renderConditionCopy);
				final Iterator<Node> renderConditionIterator = renderConditionTraverser.iterator();

				while (renderConditionIterator.hasNext()) {
					final Node renderConditionNode = renderConditionIterator.next();
					String resourceType = renderConditionNode.getProperty(ResourceResolver.PROPERTY_RESOURCE_TYPE).getString();
					if (resourceType.startsWith(RENDER_CONDITION_CORAL2_RESOURCE_TYPE_PREFIX)) {
						resourceType = resourceType.replace(RENDER_CONDITION_CORAL2_RESOURCE_TYPE_PREFIX, RENDER_CONDITION_CORAL3_RESOURCE_TYPE_PREFIX);
						renderConditionNode.setProperty(ResourceResolver.PROPERTY_RESOURCE_TYPE, resourceType);
					}
				}
			}
		}

		// collect mappings: (node in original tree) -> (node in replacement tree)
		final Map<String, String> mappings = new HashMap<>();
		// traverse nodes of newly copied replacement tree
		TreeTraverser traverser = new TreeTraverser(copy);
		Iterator<Node> nodeIterator = traverser.iterator();
		while (nodeIterator.hasNext()) {
			final Node node = nodeIterator.next();
			// iterate over all properties
			final PropertyIterator propertyIterator = node.getProperties();
			Node rewritePropertiesNode = null;

			if (node.hasNode(NN_CQ_REWRITE_PROPERTIES)) {
				rewritePropertiesNode = node.getNode(NN_CQ_REWRITE_PROPERTIES);
			}

			while (propertyIterator.hasNext()) {
				final Property property = propertyIterator.nextProperty();
				// skip protected properties
				if (property.getDefinition().isProtected()) {
					continue;
				}

				// add mapping to collection
				if (PROPERTY_MAP_CHILDREN.equals(property.getName())) {
					mappings.put(property.getString(), node.getPath());
					// remove property, as we don't want it to be part of the result
					property.remove();
					continue;
				}
				// add single node to final nodes
				if (PROPERTY_IS_FINAL.equals(property.getName())) {
					if (!treeIsFinal) {
						finalNodes.add(node);
					}
					property.remove();
					continue;
				}
				// set value from original tree in case this is a mapped property
				final Property mappedProperty = this.mapProperty(root, property);

				if ((mappedProperty != null) && (rewritePropertiesNode != null)) {
					if (rewritePropertiesNode.hasProperty("./" + mappedProperty.getName())) {
						this.rewriteProperty(property, rewritePropertiesNode.getProperty("./" + mappedProperty.getName()));
					}
				}
			}

			// remove <cq:rewriteProperties> node post-mapping
			if (rewritePropertiesNode != null) {
				rewritePropertiesNode.remove();
			}
		}

		// copy children from original tree to replacement tree according to the mappings found
		final Session session = root.getSession();
		for (final Map.Entry<String, String> mapping : mappings.entrySet()) {
			if (!root.hasNode(mapping.getKey())) {
				// the node specified in the mapping does not exist in the original tree
				continue;
			}
			final Node source = root.getNode(mapping.getKey());
			final Node destination = session.getNode(mapping.getValue());
			final NodeIterator iterator = source.getNodes();
			// copy over the source's children to the destination
			while (iterator.hasNext()) {
				final Node child = iterator.nextNode();
				JcrUtil.copy(child, destination, child.getName());
			}
		}

		// we add the complete subtree to the final nodes
		if (treeIsFinal) {
			traverser = new TreeTraverser(copy);
			nodeIterator = traverser.iterator();
			while (nodeIterator.hasNext()) {
				finalNodes.add(nodeIterator.next());
			}
		}

		// remove original tree and return rewritten tree
		root.remove();
		return copy;
	}

	/**
	 * Replaces the value of a mapped property with a value from the original tree.
	 *
	 * @param root
	 *            the root node of the original tree
	 * @param property
	 *            the (potentially) mapped property in the replacement copy tree
	 * @return the mapped property if there was a successful mapping, null otherwise
	 */
	private Property mapProperty(final Node root, final Property property) throws RepositoryException {
		if (property.getType() != PropertyType.STRING) {
			// a mapped property must be of type string
			return null;
		}

		// array containing the expressions: ${<path>}
		Value[] values;
		if (property.isMultiple()) {
			values = property.getValues();
		} else {
			values = new Value[1];
			values[0] = property.getValue();
		}

		boolean deleteProperty = false;
		for (final Value value : values) {
			final Matcher matcher = MAPPED_PATTERN.matcher(value.getString());
			if (matcher.matches()) {
				// this is a mapped property, we will delete it if the mapped destination
				// property doesn't exist
				deleteProperty = true;
				String path = matcher.group(2);
				// unwrap quoted property paths
				path = StringUtils.removeStart(StringUtils.stripEnd(path, "\'"), "\'");
				if (root.hasProperty(path)) {
					// replace property by mapped value in the original tree
					final Property originalProperty = root.getProperty(path);
					final String name = property.getName();
					final Node parent = property.getParent();
					property.remove();
					final Property newProperty = JcrUtil.copy(originalProperty, parent, name);

					// negate boolean properties if negation character has been set
					final String negate = matcher.group(1);
					if ("!".equals(negate)) {

						if (originalProperty.getType() == PropertyType.BOOLEAN) {
							newProperty.setValue(!newProperty.getBoolean());

						} else if ((originalProperty.getType() == PropertyType.STRING)
								&& ("true".equals(newProperty.getString()) || "false".equals(newProperty.getString()))) {
							newProperty.setValue(!newProperty.getBoolean());
						}
					}
					// the mapping was successful
					deleteProperty = false;
					break;
				} else {
					final String defaultValue = matcher.group(4);
					if (defaultValue != null) {
						if (property.isMultiple()) {
							// the property is multiple in the replacement,
							// recreate it so we can set the property to the default
							final String name = property.getName();
							final Node parent = property.getParent();
							property.remove();
							parent.setProperty(name, defaultValue);
						} else {
							property.setValue(defaultValue);
						}

						deleteProperty = false;
						break;
					}
				}
			}
		}
		if (deleteProperty) {
			// mapped destination does not exist, we don't include the property in replacement tree
			property.remove();
			return null;
		}

		return property;
	}

	/**
	 * Applies a string rewrite to a property.
	 *
	 * @param property
	 *            the property to rewrite
	 * @param rewriteProperty
	 *            the property that defines the string rewrite
	 */
	private void rewriteProperty(final Property property, final Property rewriteProperty) throws RepositoryException {
		if (property.getType() == PropertyType.STRING) {
			if (rewriteProperty.isMultiple() && (rewriteProperty.getValues().length == 2)) {
				final Value[] rewrite = rewriteProperty.getValues();

				if ((rewrite[0].getType() == PropertyType.STRING) && (rewrite[1].getType() == PropertyType.STRING)) {
					final String pattern = rewrite[0].toString();
					final String replacement = rewrite[1].toString();

					final Pattern compiledPattern = Pattern.compile(pattern);
					final Matcher matcher = compiledPattern.matcher(property.getValue().toString());
					property.setValue(matcher.replaceAll(replacement));
				}
			}
		}
	}

	/**
	 * Adds property mappings on a replacement node for Granite common attributes.
	 *
	 * @param root
	 *            the root node
	 * @param node
	 *            the replacement node
	 */
	private void addCommonAttrMappings(final Node root, final Node node) throws RepositoryException {
		for (final String property : GRANITE_COMMON_ATTR_PROPERTIES) {
			final String[] mapping = { "${./" + property + "}", "${\'./granite:" + property + "\'}" };
			this.mapProperty(root, node.setProperty("granite:" + property, mapping));
		}

		if (root.hasNode(NN_GRANITE_DATA)) {
			// the root has granite:data defined, copy it before applying data-* properties
			JcrUtil.copy(root.getNode(NN_GRANITE_DATA), node, NN_GRANITE_DATA);
		}

		// map data-* prefixed properties to granite:data child
		final PropertyIterator propertyIterator = root.getProperties(DATA_PREFIX + "*");
		while (propertyIterator.hasNext()) {
			final Property property = propertyIterator.nextProperty();
			final String name = property.getName();

			// skip protected properties
			if (property.getDefinition().isProtected()) {
				continue;
			}

			// add the granite:data child if necessary
			if (!node.hasNode(NN_GRANITE_DATA)) {
				node.addNode(NN_GRANITE_DATA);
			}

			// set up the property mapping
			if (node.hasNode(NN_GRANITE_DATA)) {
				final Node dataNode = node.getNode(NN_GRANITE_DATA);
				final String nameWithoutPrefix = name.substring(DATA_PREFIX.length());
				this.mapProperty(root, dataNode.setProperty(nameWithoutPrefix, "${./" + name + "}"));
			}
		}
	}

	@Override
	public String toString() {
		String path = null;
		try {
			path = this.ruleNode.getPath();
		} catch (final RepositoryException e) {
			// ignore
		}
		return "NodeBasedRewriteRule[" + (path == null ? "" : "path=" + path + ",") + "ranking=" + this.getRanking() + "]";
	}

	@Override
	public int getRanking() {
		if (this.ranking == null) {
			try {
				if (this.ruleNode.hasProperty(PROPERTY_RANKING)) {
					final long ranking = this.ruleNode.getProperty(PROPERTY_RANKING).getLong();
					this.ranking = new Long(ranking).intValue();
				} else {
					this.ranking = Integer.MAX_VALUE;
				}
			} catch (final RepositoryException e) {
				this.logger.warn("Caught exception while reading the " + PROPERTY_RANKING + " property");
			}
		}
		return this.ranking;
	}
}
