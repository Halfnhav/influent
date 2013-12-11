/**
 * Copyright (c) 2013 Oculus Info Inc.
 * http://www.oculusinfo.com/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package influent.kiva.server.spi;

import influent.cluster.DynamicClustering;
import influent.entity.clustering.GeneralEntityClusterer;
import influent.entity.clustering.utils.PropertyManager;
import influent.idl.FL_Clustering;
import influent.idl.FL_ClusteringDataAccess;
import influent.idl.FL_DataAccess;
import influent.idl.FL_Geocoding;
import influent.midtier.api.EntityClusterer;

import java.io.InputStream;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;


/**
 *
 */
public class KivaFLDynamicClusteringModule extends AbstractModule {

	@Override
	protected void configure() {
//		bind(EntityClusterer.class).to(MultiStageEntityClusterer.class);
		bind(EntityClusterer.class).to(GeneralEntityClusterer.class);
		bind(FL_Clustering.class).to(DynamicClustering.class);
		bind(FL_ClusteringDataAccess.class).to(DynamicClustering.class);
	}
	
	/*
	 * Provide the clustering service
	 */
	@Provides @Singleton
	public DynamicClustering clustering (
			FL_DataAccess dataAccess,
			FL_Geocoding geocoding,
			EntityClusterer clusterer,
			@Named("influent.midtier.ehcache.config") String ehCacheConfig,
			@Named("influent.dynamic.clustering.cache.name") String cacheName
	) {

		try {
			InputStream configStream = KivaFLDynamicClusteringModule.class.getResourceAsStream("/clusterer.properties");
			return new DynamicClustering(
				dataAccess, 
				geocoding, 
				clusterer, 
				new PropertyManager(configStream),
				ehCacheConfig,
				cacheName
			);
		} catch (Exception e) {
			addError("Failed to load Clustering", e);
			return null;
		}
	}
	
}
