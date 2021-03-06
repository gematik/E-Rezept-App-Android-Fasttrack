/*
 * Copyright (c) 2021 gematik GmbH
 * 
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the Licence);
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 *     https://joinup.ec.europa.eu/software/page/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * 
 */

package de.gematik.ti.erp.app.api

import org.hl7.fhir.r4.model.Bundle
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface PharmacySearchService {

    @GET("api/Location")
    suspend fun search(
        @Query("name") names: List<String>,
        @QueryMap attributes: Map<String, String>
    ): Response<Bundle>

    // paging realised through session referenced by the previous bundle id
    @GET("api")
    suspend fun searchByBundle(
        @Query("_getpages") bundleId: String,
        @Query("_getpagesoffset") offset: Int,
        @Query("_count") count: Int,
    ): Response<Bundle>
}
