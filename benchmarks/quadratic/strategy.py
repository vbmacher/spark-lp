import numpy as np
import scipy
from scipy import sparse
from scipy.sparse.linalg import cg, LinearOperator
import json, time
from pathlib import Path
records=[]
for n,m,k,density in [(128,16,32,.05),(512,32,64,.02),(128,16,64,1.)]:
 rng=np.random.default_rng(7000)
 F=sparse.random(k,n,density=density,random_state=rng,data_rvs=lambda z:rng.standard_normal(z),format='csr')
 A=sparse.random(m,n,density=.2,random_state=rng,data_rvs=lambda z:rng.standard_normal(z),format='csr')
 A=A+sparse.eye(m,n,format='csr')
 rhs=rng.standard_normal(m)
 start=time.perf_counter();H=np.eye(n)+(F.T@F).toarray();N=A@np.linalg.solve(H,A.T.toarray());ref=np.linalg.solve(N,rhs);direct=time.perf_counter()-start
 C=sparse.bmat([[A,None,None],[F,-sparse.eye(k),sparse.eye(k)]],format='csr')
 W=np.r_[np.ones(n),np.full(2*k,.5)]; b=np.r_[rhs,np.zeros(k)]
 op=LinearOperator((m+k,m+k),matvec=lambda v:C@(W*(C.T@v)))
 diag=np.asarray(C.power(2)@W).ravel();pre=LinearOperator(op.shape,matvec=lambda v:v/diag)
 count=[0]
 def callback(x): count[0]+=1
 start=time.perf_counter();y,info=cg(op,b,M=pre,rtol=1e-10,atol=1e-12,maxiter=2000,callback=callback);elapsed=time.perf_counter()-start
 records.append(dict(n=n,m=m,factors=k,density=density,seed=7000,scipy=scipy.__version__,status=int(info),iterations=count[0],lifted_seconds=elapsed,reference_seconds=direct,dual_relative_error=float(np.linalg.norm(y[:m]-ref)/(1+np.linalg.norm(ref))),normal_residual=float(np.linalg.norm(op@y-b)),lifted_sparse_bytes=C.data.nbytes+C.indices.nbytes+C.indptr.nbytes,reference_hessian_bytes=H.nbytes,scope='single-process strategy experiment; reference dense Hessian used only for verification'))
Path(__file__).with_name('strategy-results.json').write_text(json.dumps(records,indent=2)+'\n')
print(json.dumps(records,indent=2))
