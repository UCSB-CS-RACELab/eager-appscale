package edu.ucsb.cs.roots.utils;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.rosuda.REngine.Rserve.RConnection;
import org.rosuda.REngine.Rserve.RserveException;

public final class RConnectionPoolFactory extends BasePooledObjectFactory<RConnection> {

    @Override
    public RConnection create() throws RserveException {
        RConnection r = new RConnection();
        r.eval("library('dtw')");
        return r;
    }

    @Override
    public PooledObject<RConnection> wrap(RConnection rConnection) {
        return new DefaultPooledObject<>(rConnection);
    }

    @Override
    public void destroyObject(PooledObject<RConnection> p) throws Exception {
        super.destroyObject(p);
        p.getObject().close();
    }
}
