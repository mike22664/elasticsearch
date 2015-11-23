/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.ingest.processor.uppercase;

import org.elasticsearch.ingest.processor.AbstractStringProcessor;
import org.elasticsearch.ingest.processor.AbstractStringProcessorTestCase;

import java.util.Collection;
import java.util.Locale;

public class UppercaseProcessorTests extends AbstractStringProcessorTestCase {

    @Override
    protected AbstractStringProcessor newProcessor(Collection<String> fields) {
        return new UppercaseProcessor(fields);
    }

    @Override
    protected String expectedResult(String input) {
        return input.toUpperCase(Locale.ROOT);
    }
}
